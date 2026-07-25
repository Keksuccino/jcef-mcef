// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#ifndef JCEF_NATIVE_PERMISSION_DISPATCH_LIFECYCLE_H_
#define JCEF_NATIVE_PERMISSION_DISPATCH_LIFECYCLE_H_
#pragma once

#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <mutex>
#include <thread>
#include <unordered_map>

class PermissionDispatchLifecycle;

// Move-only lease held for exactly as long as JCEF retains an extra CEF
// permission callback reference. Close cannot complete until every lease
// releases its callback and handler owner.
class PermissionCallbackLease {
 public:
  PermissionCallbackLease() = default;
  PermissionCallbackLease(PermissionCallbackLease&& other) noexcept;
  PermissionCallbackLease& operator=(PermissionCallbackLease&& other) noexcept;
  ~PermissionCallbackLease();

  explicit operator bool() const { return lifecycle_ != nullptr; }
  void Release();

 private:
  friend class PermissionDispatchLifecycle;

  explicit PermissionCallbackLease(PermissionDispatchLifecycle* lifecycle) : lifecycle_(lifecycle) {}

  PermissionDispatchLifecycle* lifecycle_ = nullptr;

  PermissionCallbackLease(const PermissionCallbackLease&) = delete;
  PermissionCallbackLease& operator=(const PermissionCallbackLease&) = delete;
};

// Move-only permit proving that dispatch began while the lifecycle was open.
// The lease protects retained callback lifetime, while this shorter permit
// independently keeps Close from passing an in-flight CEF call and detects a
// reentrant Close on the dispatching thread instead of self-deadlocking.
class PermissionDispatchPermit {
 public:
  PermissionDispatchPermit() = default;
  PermissionDispatchPermit(PermissionDispatchPermit&& other) noexcept;
  PermissionDispatchPermit& operator=(PermissionDispatchPermit&& other) noexcept;
  ~PermissionDispatchPermit();

  explicit operator bool() const { return lifecycle_ != nullptr; }
  void Release();

 private:
  friend class PermissionDispatchLifecycle;

  PermissionDispatchPermit(PermissionDispatchLifecycle* lifecycle, std::thread::id dispatch_thread)
      : lifecycle_(lifecycle), dispatch_thread_(dispatch_thread) {}

  PermissionDispatchLifecycle* lifecycle_ = nullptr;
  std::thread::id dispatch_thread_;

  PermissionDispatchPermit(const PermissionDispatchPermit&) = delete;
  PermissionDispatchPermit& operator=(const PermissionDispatchPermit&) = delete;
};

class PermissionDispatchLifecycle {
 public:
  enum class Phase {
    kClosed,
    kOpen,
    kClosing,
  };

  PermissionCallbackLease AcquireLease();
  PermissionDispatchPermit TryBeginDispatch(const PermissionCallbackLease& lease);

  // Controller operations are idempotent but must be externally serialized with
  // the surrounding CEF initialize/shutdown operation. They return false for
  // overlapping controller operations or a Close invoked reentrantly by the
  // actively dispatching thread, avoiding an otherwise unavoidable
  // self-deadlock. AcquireLease, TryBeginDispatch and token release remain
  // thread-safe; leases are intentionally movable and have no thread affinity.
  bool Open();
  bool Close();

  Phase phase_for_testing() const;
  bool WaitForPhaseForTesting(Phase phase, std::chrono::steady_clock::duration timeout);
  bool WaitForActiveLeasesForTesting(size_t count, std::chrono::steady_clock::duration timeout);

 private:
  friend class PermissionCallbackLease;
  friend class PermissionDispatchPermit;

  void ReleaseLease();
  void EndDispatch(std::thread::id dispatch_thread);
  bool CurrentThreadIsDispatchingLocked() const;

  mutable std::mutex lock_;
  std::condition_variable condition_;
  Phase phase_ = Phase::kClosed;
  size_t active_leases_ = 0;
  std::unordered_map<std::thread::id, size_t> dispatch_threads_;
  bool controller_operation_active_ = false;
};

PermissionDispatchLifecycle& GetPermissionDispatchLifecycle();
void OpenPermissionDispatchLifecycle();
void ClosePermissionDispatchLifecycle();

#endif  // JCEF_NATIVE_PERMISSION_DISPATCH_LIFECYCLE_H_
