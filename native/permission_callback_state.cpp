// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "permission_callback_state.h"

#include <algorithm>
#include <utility>

MediaAccessCallbackState::MediaAccessCallbackState(int browser_identifier, const CefRefPtr<CefMediaAccessCallback>& callback, PermissionDispatchLifecycle* dispatch_lifecycle)
    : TypedPermissionCallbackState(PermissionCallbackKind::kMediaAccess, browser_identifier, 0, callback, dispatch_lifecycle) {}

bool MediaAccessCallbackState::Continue(uint32_t allowed_permissions) {
  return Complete(MediaAccessAction{false, allowed_permissions});
}

bool MediaAccessCallbackState::Cancel() {
  return Complete(MediaAccessAction{true, CEF_MEDIA_PERMISSION_NONE});
}

void MediaAccessCallbackState::Dispatch(CefRefPtr<CefMediaAccessCallback> callback, MediaAccessAction action) {
  // CEF 151 explicitly accepts these continuations from any thread and performs
  // its own UI-thread dispatch. Calling directly preserves ordering and avoids
  // a redundant or failing shutdown post.
  if (action.cancel)
    callback->Cancel();
  else
    callback->Continue(action.allowed_permissions);
}

PermissionPromptCallbackState::PermissionPromptCallbackState(int browser_identifier, uint64_t prompt_id, const CefRefPtr<CefPermissionPromptCallback>& callback, PermissionDispatchLifecycle* dispatch_lifecycle)
    : TypedPermissionCallbackState(PermissionCallbackKind::kPermissionPrompt, browser_identifier, prompt_id, callback, dispatch_lifecycle) {}

bool PermissionPromptCallbackState::Continue(cef_permission_request_result_t result) {
  return Complete(result);
}

void PermissionPromptCallbackState::Dispatch(CefRefPtr<CefPermissionPromptCallback> callback, cef_permission_request_result_t result) {
  // CEF defers synchronous prompt continuation until OnShowPermissionPrompt
  // returns and accepts asynchronous calls from any thread.
  callback->Continue(result);
}

bool PermissionCallbackRegistry::Add(CefRefPtr<PermissionCallbackState> callback) {
  std::lock_guard<std::mutex> lock_scope(lock_);
  if (shutdown_)
    return false;
  callbacks_.push_back(std::move(callback));
  return true;
}

void PermissionCallbackRegistry::Remove(PermissionCallbackState* callback) {
  std::lock_guard<std::mutex> lock_scope(lock_);
  std::erase_if(callbacks_, [callback](const CefRefPtr<PermissionCallbackState>& existing) { return existing.get() == callback; });
}

CefRefPtr<PermissionCallbackState> PermissionCallbackRegistry::TakePromptCallback(int browser_identifier, uint64_t prompt_id) {
  std::lock_guard<std::mutex> lock_scope(lock_);
  auto iterator = std::find_if(callbacks_.begin(), callbacks_.end(), [browser_identifier, prompt_id](const CefRefPtr<PermissionCallbackState>& callback) { return callback->kind() == PermissionCallbackKind::kPermissionPrompt && callback->browser_identifier() == browser_identifier && callback->prompt_id() == prompt_id; });
  if (iterator == callbacks_.end())
    return nullptr;
  CefRefPtr<PermissionCallbackState> callback = *iterator;
  callbacks_.erase(iterator);
  return callback;
}

void PermissionCallbackRegistry::InvalidateBrowserCallbacks(int browser_identifier) {
  std::vector<CefRefPtr<PermissionCallbackState>> callbacks;
  {
    std::lock_guard<std::mutex> lock_scope(lock_);
    auto first_match = std::stable_partition(callbacks_.begin(), callbacks_.end(), [browser_identifier](const CefRefPtr<PermissionCallbackState>& callback) { return callback->browser_identifier() != browser_identifier; });
    callbacks.assign(first_match, callbacks_.end());
    callbacks_.erase(first_match, callbacks_.end());
  }
  for (const auto& callback : callbacks)
    callback->Invalidate();
}

void PermissionCallbackRegistry::InvalidateAllCallbacks() {
  std::vector<CefRefPtr<PermissionCallbackState>> callbacks;
  {
    std::lock_guard<std::mutex> lock_scope(lock_);
    shutdown_ = true;
    callbacks.swap(callbacks_);
  }
  for (const auto& callback : callbacks)
    callback->Invalidate();
}
