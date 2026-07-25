// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#ifndef JCEF_NATIVE_PERMISSION_CALLBACK_STATE_H_
#define JCEF_NATIVE_PERMISSION_CALLBACK_STATE_H_
#pragma once

#include <cstdint>
#include <mutex>
#include <optional>
#include <utility>
#include <vector>

#include "include/cef_permission_handler.h"

#include "permission_dispatch_lifecycle.h"
#include "permission_util.h"

enum class PermissionCallbackKind {
  kMediaAccess,
  kPermissionPrompt,
};

class PermissionCallbackState;

class PermissionCallbackOwner : public virtual CefBaseRefCounted {
 public:
  virtual void OnPermissionCallbackTerminal(PermissionCallbackState* callback) = 0;
};

// Java owns this proxy instead of the libcef callback directly. Browser
// invalidation makes a Java-retained proxy inert without blocking CEF UI. A
// callback already dispatching remains protected by PermissionDispatchLifecycle
// until its local CEF reference is released.
class PermissionCallbackState : public CefBaseRefCounted {
 public:
  PermissionCallbackState(PermissionCallbackKind kind, int browser_identifier, uint64_t prompt_id, PermissionDispatchLifecycle* dispatch_lifecycle)
      : kind_(kind),
        browser_identifier_(browser_identifier),
        prompt_id_(prompt_id),
        dispatch_lifecycle_(dispatch_lifecycle ? dispatch_lifecycle : &GetPermissionDispatchLifecycle()) {}

  PermissionCallbackKind kind() const { return kind_; }
  int browser_identifier() const { return browser_identifier_; }
  uint64_t prompt_id() const { return prompt_id_; }

  virtual void Activate(CefRefPtr<PermissionCallbackOwner> owner) = 0;
  virtual void Abandon() = 0;
  virtual void Invalidate() = 0;

 protected:
  ~PermissionCallbackState() override = default;
  PermissionDispatchLifecycle* dispatch_lifecycle() const {
    return dispatch_lifecycle_;
  }

 private:
  const PermissionCallbackKind kind_;
  const int browser_identifier_;
  const uint64_t prompt_id_;
  PermissionDispatchLifecycle* const dispatch_lifecycle_;

  IMPLEMENT_REFCOUNTING(PermissionCallbackState);
};

template <class Callback, class Action>
class TypedPermissionCallbackState : public PermissionCallbackState {
 public:
  TypedPermissionCallbackState(PermissionCallbackKind kind, int browser_identifier, uint64_t prompt_id, const CefRefPtr<Callback>& callback, PermissionDispatchLifecycle* dispatch_lifecycle)
      : PermissionCallbackState(kind, browser_identifier, prompt_id, dispatch_lifecycle),
        lease_(this->dispatch_lifecycle()->AcquireLease()),
        callback_(lease_ ? callback : nullptr) {}

  bool IsValid() const {
    std::lock_guard<std::mutex> lock_scope(lock_);
    return lease_ && callback_;
  }

  void Activate(CefRefPtr<PermissionCallbackOwner> owner) override {
    CefRefPtr<Callback> callback;
    CefRefPtr<PermissionCallbackOwner> terminal_owner;
    std::optional<Action> action;
    PermissionCallbackLease lease;
    PermissionDispatchPermit dispatch_permit;
    bool dispatch = false;
    {
      std::lock_guard<std::mutex> lock_scope(lock_);
      if (resolution_ != Resolution::kPending)
        return;
      resolution_ = Resolution::kActive;
      owner_ = owner;
      if (completion_gate_.IsClaimed() && action_) {
        action = action_;
        dispatch_permit = dispatch_lifecycle()->TryBeginDispatch(lease_);
        callback = callback_;
        callback_ = nullptr;
        lease = std::move(lease_);
        if (dispatch_permit && callback) {
          dispatch = true;
        } else {
          // Close may win immediately before activation. The lease keeps Close
          // blocked while the denied path releases every CEF/handler reference
          // and unregisters this state.
          resolution_ = Resolution::kAbandoned;
          terminal_owner = owner_;
          owner_ = nullptr;
        }
      }
    }
    if (dispatch) {
      DispatchAndFinish(callback, *action, std::move(dispatch_permit), std::move(lease));
    } else if (callback || terminal_owner || lease) {
      ReleaseResources(std::move(callback), std::move(terminal_owner), std::move(dispatch_permit), std::move(lease), true);
    }
  }

  void Abandon() override { Invalidate(); }

  void Invalidate() override {
    CefRefPtr<Callback> callback;
    CefRefPtr<PermissionCallbackOwner> owner;
    PermissionCallbackLease lease;
    {
      std::lock_guard<std::mutex> lock_scope(lock_);
      resolution_ = Resolution::kAbandoned;
      callback = callback_;
      callback_ = nullptr;
      owner = owner_;
      owner_ = nullptr;
      lease = std::move(lease_);
    }
    // Never release a handler or CEF callback while holding the state lock. If
    // dispatch already owns the callback/lease locally, this returns
    // immediately and global lifecycle close drains that dispatch. Registry
    // invalidation removes the state before invoking this method, so no
    // terminal notification is necessary here.
    ReleaseResources(std::move(callback), std::move(owner), PermissionDispatchPermit(), std::move(lease), false);
  }

 protected:
  bool Complete(Action action) {
    if (!completion_gate_.TryClaim())
      return false;

    CefRefPtr<Callback> callback;
    CefRefPtr<PermissionCallbackOwner> terminal_owner;
    PermissionCallbackLease lease;
    PermissionDispatchPermit dispatch_permit;
    bool dispatch = false;
    {
      std::lock_guard<std::mutex> lock_scope(lock_);
      if (resolution_ == Resolution::kAbandoned)
        return false;
      action_ = action;
      if (resolution_ == Resolution::kActive) {
        dispatch_permit = dispatch_lifecycle()->TryBeginDispatch(lease_);
        callback = callback_;
        callback_ = nullptr;
        lease = std::move(lease_);
        if (dispatch_permit && callback) {
          dispatch = true;
        } else {
          // A closing lifecycle rejects the CEF call but not cleanup. The
          // pre-existing lease guarantees that Close cannot pass its barrier
          // until this cleanup finishes.
          resolution_ = Resolution::kAbandoned;
          terminal_owner = owner_;
          owner_ = nullptr;
        }
      }
    }
    if (dispatch) {
      DispatchAndFinish(callback, action, std::move(dispatch_permit), std::move(lease));
    } else if (callback || terminal_owner || lease) {
      ReleaseResources(std::move(callback), std::move(terminal_owner), std::move(dispatch_permit), std::move(lease), true);
    }
    return true;
  }

  virtual void Dispatch(CefRefPtr<Callback> callback, Action action) = 0;

 private:
  enum class Resolution {
    kPending,
    kActive,
    kAbandoned,
  };

  void DispatchAndFinish(CefRefPtr<Callback> callback, Action action, PermissionDispatchPermit dispatch_permit, PermissionCallbackLease lease) {
    Dispatch(callback, action);
    // Release the local CToCpp callback and all handler ownership before the
    // lease. Global close can therefore publish CLOSED only after no permission
    // dispatch can touch libcef/JNI state.
    callback = nullptr;

    CefRefPtr<PermissionCallbackOwner> owner;
    {
      std::lock_guard<std::mutex> lock_scope(lock_);
      owner = owner_;
      owner_ = nullptr;
    }
    ReleaseResources(nullptr, std::move(owner), std::move(dispatch_permit), std::move(lease), true);
  }

  void ReleaseResources(CefRefPtr<Callback> callback, CefRefPtr<PermissionCallbackOwner> owner, PermissionDispatchPermit dispatch_permit, PermissionCallbackLease lease, bool notify_owner) {
    callback = nullptr;
    if (notify_owner && owner)
      owner->OnPermissionCallbackTerminal(this);
    owner = nullptr;
    dispatch_permit.Release();
    lease.Release();
  }

  mutable std::mutex lock_;
  Resolution resolution_ = Resolution::kPending;
  permission_util::OneShotGate completion_gate_;
  std::optional<Action> action_;
  // Member destruction is reverse declaration order, preserving owner ->
  // callback -> lease even if an exceptional construction/destruction path
  // bypasses explicit terminal cleanup.
  PermissionCallbackLease lease_;
  CefRefPtr<Callback> callback_;
  CefRefPtr<PermissionCallbackOwner> owner_;
};

struct MediaAccessAction {
  bool cancel = false;
  uint32_t allowed_permissions = CEF_MEDIA_PERMISSION_NONE;
};

class MediaAccessCallbackState final
    : public TypedPermissionCallbackState<CefMediaAccessCallback,
                                          MediaAccessAction> {
 public:
  MediaAccessCallbackState(int browser_identifier, const CefRefPtr<CefMediaAccessCallback>& callback, PermissionDispatchLifecycle* dispatch_lifecycle = nullptr);

  bool Continue(uint32_t allowed_permissions);
  bool Cancel();

 private:
  void Dispatch(CefRefPtr<CefMediaAccessCallback> callback, MediaAccessAction action) override;
};

class PermissionPromptCallbackState final
    : public TypedPermissionCallbackState<CefPermissionPromptCallback,
                                          cef_permission_request_result_t> {
 public:
  PermissionPromptCallbackState(int browser_identifier, uint64_t prompt_id, const CefRefPtr<CefPermissionPromptCallback>& callback, PermissionDispatchLifecycle* dispatch_lifecycle = nullptr);

  bool Continue(cef_permission_request_result_t result);

 private:
  void Dispatch(CefRefPtr<CefPermissionPromptCallback> callback, cef_permission_request_result_t result) override;
};

// Owns callback states until completion or explicit invalidation. Registration
// never prunes a claimed state opportunistically while its completion cleanup
// is still in flight.
class PermissionCallbackRegistry {
 public:
  bool Add(CefRefPtr<PermissionCallbackState> callback);
  void Remove(PermissionCallbackState* callback);
  CefRefPtr<PermissionCallbackState> TakePromptCallback(int browser_identifier, uint64_t prompt_id);
  void InvalidateBrowserCallbacks(int browser_identifier);
  void InvalidateAllCallbacks();

 private:
  std::mutex lock_;
  std::vector<CefRefPtr<PermissionCallbackState>> callbacks_;
  bool shutdown_ = false;
};

#endif  // JCEF_NATIVE_PERMISSION_CALLBACK_STATE_H_
