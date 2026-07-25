// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "permission_dispatch_lifecycle.h"

#include <cstdio>
#include <cstdlib>
#include <utility>

namespace {

enum class GlobalLifecyclePhase {
  kNeverOpened,
  kOpen,
  kPermanentlyClosed,
};

std::mutex& GetGlobalLifecycleControllerLock() {
  static std::mutex* lock = new std::mutex();
  return *lock;
}

GlobalLifecyclePhase& GetGlobalLifecyclePhase() {
  static GlobalLifecyclePhase* phase = new GlobalLifecyclePhase(GlobalLifecyclePhase::kNeverOpened);
  return *phase;
}

[[noreturn]] void FailGlobalLifecycleContract(const char* operation) {
  // This path may run while the dynamically loaded CEF framework is
  // unavailable. Keep the diagnostic independent from CEF logging and fail
  // before an unsafe CefShutdown/reinitialize.
  std::fprintf(stderr, "JCEF permission lifecycle contract violation during %s\n", operation);
  std::abort();
}

template <class CountMap>
void IncrementThreadCount(CountMap& counts, std::thread::id thread) {
  ++counts[thread];
}

template <class CountMap>
void DecrementThreadCount(CountMap& counts, std::thread::id thread) {
  const auto existing = counts.find(thread);
  if (existing == counts.end())
    return;
  if (--existing->second == 0)
    counts.erase(existing);
}

}  // namespace

PermissionCallbackLease::PermissionCallbackLease(PermissionCallbackLease&& other) noexcept
    : lifecycle_(other.lifecycle_) {
  other.lifecycle_ = nullptr;
}

PermissionCallbackLease& PermissionCallbackLease::operator=(PermissionCallbackLease&& other) noexcept {
  if (this == &other)
    return *this;
  Release();
  lifecycle_ = other.lifecycle_;
  other.lifecycle_ = nullptr;
  return *this;
}

PermissionCallbackLease::~PermissionCallbackLease() {
  Release();
}

void PermissionCallbackLease::Release() {
  PermissionDispatchLifecycle* lifecycle = lifecycle_;
  if (!lifecycle)
    return;
  lifecycle_ = nullptr;
  lifecycle->ReleaseLease();
}

PermissionDispatchPermit::PermissionDispatchPermit(PermissionDispatchPermit&& other) noexcept
    : lifecycle_(other.lifecycle_), dispatch_thread_(other.dispatch_thread_) {
  other.lifecycle_ = nullptr;
  other.dispatch_thread_ = std::thread::id();
}

PermissionDispatchPermit& PermissionDispatchPermit::operator=(PermissionDispatchPermit&& other) noexcept {
  if (this == &other)
    return *this;
  Release();
  lifecycle_ = other.lifecycle_;
  dispatch_thread_ = other.dispatch_thread_;
  other.lifecycle_ = nullptr;
  other.dispatch_thread_ = std::thread::id();
  return *this;
}

PermissionDispatchPermit::~PermissionDispatchPermit() {
  Release();
}

void PermissionDispatchPermit::Release() {
  PermissionDispatchLifecycle* lifecycle = lifecycle_;
  if (!lifecycle)
    return;
  const std::thread::id dispatch_thread = dispatch_thread_;
  lifecycle_ = nullptr;
  dispatch_thread_ = std::thread::id();
  lifecycle->EndDispatch(dispatch_thread);
}

PermissionCallbackLease PermissionDispatchLifecycle::AcquireLease() {
  std::lock_guard<std::mutex> lock_scope(lock_);
  if (phase_ != Phase::kOpen)
    return PermissionCallbackLease();
  ++active_leases_;
  condition_.notify_all();
  return PermissionCallbackLease(this);
}

PermissionDispatchPermit PermissionDispatchLifecycle::TryBeginDispatch(const PermissionCallbackLease& lease) {
  std::lock_guard<std::mutex> lock_scope(lock_);
  if (lease.lifecycle_ != this || phase_ != Phase::kOpen)
    return PermissionDispatchPermit();
  const std::thread::id dispatch_thread = std::this_thread::get_id();
  IncrementThreadCount(dispatch_threads_, dispatch_thread);
  return PermissionDispatchPermit(this, dispatch_thread);
}

bool PermissionDispatchLifecycle::Open() {
  std::lock_guard<std::mutex> lock_scope(lock_);
  if (controller_operation_active_)
    return false;
  controller_operation_active_ = true;
  if (phase_ != Phase::kOpen)
    phase_ = Phase::kOpen;
  controller_operation_active_ = false;
  condition_.notify_all();
  return true;
}

bool PermissionDispatchLifecycle::Close() {
  std::unique_lock<std::mutex> lock_scope(lock_);
  if (controller_operation_active_ || CurrentThreadIsDispatchingLocked())
    return false;
  controller_operation_active_ = true;
  if (phase_ == Phase::kClosed) {
    controller_operation_active_ = false;
    condition_.notify_all();
    return true;
  }

  phase_ = Phase::kClosing;
  condition_.notify_all();
  condition_.wait(lock_scope, [this]() { return active_leases_ == 0 && dispatch_threads_.empty(); });
  phase_ = Phase::kClosed;
  controller_operation_active_ = false;
  condition_.notify_all();
  return true;
}

PermissionDispatchLifecycle::Phase PermissionDispatchLifecycle::phase_for_testing() const {
  std::lock_guard<std::mutex> lock_scope(lock_);
  return phase_;
}

bool PermissionDispatchLifecycle::WaitForPhaseForTesting(Phase phase, std::chrono::steady_clock::duration timeout) {
  std::unique_lock<std::mutex> lock_scope(lock_);
  return condition_.wait_for(lock_scope, timeout, [this, phase]() { return phase_ == phase; });
}

bool PermissionDispatchLifecycle::WaitForActiveLeasesForTesting(size_t count, std::chrono::steady_clock::duration timeout) {
  std::unique_lock<std::mutex> lock_scope(lock_);
  return condition_.wait_for(lock_scope, timeout, [this, count]() { return active_leases_ == count; });
}

void PermissionDispatchLifecycle::ReleaseLease() {
  std::lock_guard<std::mutex> lock_scope(lock_);
  if (active_leases_ == 0)
    return;
  --active_leases_;
  condition_.notify_all();
}

void PermissionDispatchLifecycle::EndDispatch(std::thread::id dispatch_thread) {
  std::lock_guard<std::mutex> lock_scope(lock_);
  DecrementThreadCount(dispatch_threads_, dispatch_thread);
  condition_.notify_all();
}

bool PermissionDispatchLifecycle::CurrentThreadIsDispatchingLocked() const {
  const std::thread::id current_thread = std::this_thread::get_id();
  return dispatch_threads_.contains(current_thread);
}

PermissionDispatchLifecycle& GetPermissionDispatchLifecycle() {
  // Late Java finalizers may observe already-invalidated callback wrappers.
  // Keep the empty control object alive for process lifetime and explicitly
  // close it before CEF unload.
  static PermissionDispatchLifecycle* lifecycle = new PermissionDispatchLifecycle();
  return *lifecycle;
}

void OpenPermissionDispatchLifecycle() {
  std::unique_lock<std::mutex> controller_lock(GetGlobalLifecycleControllerLock(), std::try_to_lock);
  if (!controller_lock.owns_lock())
    FailGlobalLifecycleContract("overlapping Open");
  GlobalLifecyclePhase& global_phase = GetGlobalLifecyclePhase();
  if (global_phase == GlobalLifecyclePhase::kPermanentlyClosed)
    FailGlobalLifecycleContract("Open after final Close");
  if (global_phase == GlobalLifecyclePhase::kOpen)
    return;
  if (!GetPermissionDispatchLifecycle().Open())
    FailGlobalLifecycleContract("Open");
  global_phase = GlobalLifecyclePhase::kOpen;
}

void ClosePermissionDispatchLifecycle() {
  std::unique_lock<std::mutex> controller_lock(GetGlobalLifecycleControllerLock(), std::try_to_lock);
  if (!controller_lock.owns_lock())
    FailGlobalLifecycleContract("overlapping Close");
  GlobalLifecyclePhase& global_phase = GetGlobalLifecyclePhase();
  if (global_phase == GlobalLifecyclePhase::kPermanentlyClosed)
    return;
  if (global_phase == GlobalLifecyclePhase::kNeverOpened) {
    if (!GetPermissionDispatchLifecycle().Close())
      FailGlobalLifecycleContract("pre-initialize Close");
    return;
  }
  if (!GetPermissionDispatchLifecycle().Close())
    FailGlobalLifecycleContract("Close");
  global_phase = GlobalLifecyclePhase::kPermanentlyClosed;
}
