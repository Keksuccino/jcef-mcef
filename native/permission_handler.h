// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#ifndef JCEF_NATIVE_PERMISSION_HANDLER_H_
#define JCEF_NATIVE_PERMISSION_HANDLER_H_
#pragma once

#include <jni.h>

#include "include/cef_permission_handler.h"

#include "jni_scoped_helpers.h"
#include "permission_callback_state.h"

class PermissionHandler : public CefPermissionHandler,
                          public PermissionCallbackOwner {
 public:
  PermissionHandler(JNIEnv* env, jobject handler);

  bool OnRequestMediaAccessPermission(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame, const CefString& requesting_origin, uint32_t requested_permissions, CefRefPtr<CefMediaAccessCallback> callback) override;
  bool OnShowPermissionPrompt(CefRefPtr<CefBrowser> browser, uint64_t prompt_id, const CefString& requesting_origin, uint32_t requested_permissions, CefRefPtr<CefPermissionPromptCallback> callback) override;
  void OnDismissPermissionPrompt(CefRefPtr<CefBrowser> browser, uint64_t prompt_id, cef_permission_request_result_t result) override;

  // These hooks release libcef callback references before browser or runtime
  // teardown. Media callbacks intentionally remain valid across navigation,
  // matching CEF's own contract.
  void OnBeforeClose(CefRefPtr<CefBrowser> browser);
  void OnRenderProcessTerminated(CefRefPtr<CefBrowser> browser);
  void Shutdown();
  void OnPermissionCallbackTerminal(PermissionCallbackState* callback) override;
  bool IsValid() const;
  bool IsSameJavaHandler(JNIEnv* env, jobject handler) const;

 private:
  bool RegisterAndActivate(CefRefPtr<PermissionCallbackState> callback);
  void RemoveCallback(PermissionCallbackState* callback);
  CefRefPtr<PermissionCallbackState> TakePromptCallback(int browser_identifier, uint64_t prompt_id);
  void InvalidateBrowserCallbacks(int browser_identifier);
  void InvalidateAllCallbacks();

  ScopedJNIObjectGlobal handle_;
  PermissionCallbackRegistry callbacks_;

  IMPLEMENT_REFCOUNTING(PermissionHandler);
};

#endif  // JCEF_NATIVE_PERMISSION_HANDLER_H_
