// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "permission_handler.h"

#include <utility>

#include "jni_util.h"
#include "permission_callback_jni.h"
#include "permission_util.h"
#include "util.h"

namespace {

class ScopedJNIMediaAccessCallback
    : public ScopedJNIObject<MediaAccessCallbackState> {
 public:
  ScopedJNIMediaAccessCallback(JNIEnv* env, CefRefPtr<MediaAccessCallbackState> callback)
      : ScopedJNIObject<MediaAccessCallbackState>(env, callback, "org/cef/callback/CefMediaAccessCallback_N", "CefMediaAccessCallback") {}
};

class ScopedJNIPermissionPromptCallback
    : public ScopedJNIObject<PermissionPromptCallbackState> {
 public:
  ScopedJNIPermissionPromptCallback(JNIEnv* env, CefRefPtr<PermissionPromptCallbackState> callback)
      : ScopedJNIObject<PermissionPromptCallbackState>(env, callback, "org/cef/callback/CefPermissionPromptCallback_N", "CefPermissionPromptCallback") {}
};

void DescribeAndClearPendingJNIException(JNIEnv* env) {
  if (!env || !env->ExceptionCheck())
    return;
  env->ExceptionDescribe();
  env->ExceptionClear();
}

}  // namespace

PermissionHandler::PermissionHandler(JNIEnv* env, jobject handler)
    : handle_(env, handler) {}

bool PermissionHandler::OnRequestMediaAccessPermission(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame, const CefString& requesting_origin, uint32_t requested_permissions, CefRefPtr<CefMediaAccessCallback> callback) {
  REQUIRE_UI_THREAD();
  if (!browser || !frame || !callback)
    return false;

  ScopedJNIEnv env;
  if (!env)
    return false;

  CefRefPtr<MediaAccessCallbackState> callback_state = new MediaAccessCallbackState(browser->GetIdentifier(), callback);
  if (!callback_state->IsValid())
    return false;
  ScopedJNIBrowser jbrowser(env, browser);
  ScopedJNIFrame jframe(env, frame);
  if (jframe)
    jframe.SetTemporary();
  ScopedJNIObjectLocal jrequesting_origin(env, NewJNIString(env, requesting_origin));
  ScopedJNIMediaAccessCallback jcallback(env, callback_state);
  jboolean jresult = JNI_FALSE;

  if (!jbrowser || !jframe || !jrequesting_origin || !jcallback) {
    DescribeAndClearPendingJNIException(env);
    callback_state->Abandon();
    if (jcallback)
      ReleasePermissionCallbackNativeRef<MediaAccessCallbackState>(env, jcallback.get());
    return false;
  }

  JNI_CALL_METHOD(env, handle_, "onRequestMediaAccessPermission", "(Lorg/cef/browser/CefBrowser;Lorg/cef/browser/CefFrame;Ljava/lang/String;ILorg/cef/callback/CefMediaAccessCallback;)Z", Boolean, jresult, jbrowser.get(), jframe.get(), jrequesting_origin.get(), permission_util::PermissionMaskToJNI(requested_permissions), jcallback.get());

  if (jresult != JNI_FALSE) {
    if (RegisterAndActivate(callback_state))
      return true;
    ReleasePermissionCallbackNativeRef<MediaAccessCallbackState>(env, jcallback.get());
    return false;
  }

  // A false return (including a cleared Java exception) leaves the decision to
  // CEF. Discard any callback action attempted by the Java handler so malformed
  // code cannot override that default.
  callback_state->Abandon();
  ReleasePermissionCallbackNativeRef<MediaAccessCallbackState>(env, jcallback.get());
  return false;
}

bool PermissionHandler::OnShowPermissionPrompt(CefRefPtr<CefBrowser> browser, uint64_t prompt_id, const CefString& requesting_origin, uint32_t requested_permissions, CefRefPtr<CefPermissionPromptCallback> callback) {
  REQUIRE_UI_THREAD();
  if (!browser || !callback)
    return false;

  ScopedJNIEnv env;
  if (!env)
    return false;

  CefRefPtr<PermissionPromptCallbackState> callback_state = new PermissionPromptCallbackState(browser->GetIdentifier(), prompt_id, callback);
  if (!callback_state->IsValid())
    return false;
  ScopedJNIBrowser jbrowser(env, browser);
  ScopedJNIObjectLocal jrequesting_origin(env, NewJNIString(env, requesting_origin));
  ScopedJNIPermissionPromptCallback jcallback(env, callback_state);
  jboolean jresult = JNI_FALSE;

  if (!jbrowser || !jrequesting_origin || !jcallback) {
    DescribeAndClearPendingJNIException(env);
    callback_state->Abandon();
    if (jcallback)
      ReleasePermissionCallbackNativeRef<PermissionPromptCallbackState>(env, jcallback.get());
    return false;
  }

  JNI_CALL_METHOD(env, handle_, "onShowPermissionPrompt", "(Lorg/cef/browser/CefBrowser;JLjava/lang/String;ILorg/cef/callback/CefPermissionPromptCallback;)Z", Boolean, jresult, jbrowser.get(), permission_util::PromptIdToJNI(prompt_id), jrequesting_origin.get(), permission_util::PermissionMaskToJNI(requested_permissions), jcallback.get());

  if (jresult != JNI_FALSE) {
    if (RegisterAndActivate(callback_state))
      return true;
    ReleasePermissionCallbackNativeRef<PermissionPromptCallbackState>(env, jcallback.get());
    return false;
  }

  callback_state->Abandon();
  ReleasePermissionCallbackNativeRef<PermissionPromptCallbackState>(env, jcallback.get());
  return false;
}

void PermissionHandler::OnDismissPermissionPrompt(CefRefPtr<CefBrowser> browser, uint64_t prompt_id, cef_permission_request_result_t result) {
  REQUIRE_UI_THREAD();
  if (!browser)
    return;

  // Make a Java-retained continuation inert before notifying application code.
  // CEF invokes this method before resolving the underlying Chromium permission
  // request.
  CefRefPtr<PermissionCallbackState> callback_state = TakePromptCallback(browser->GetIdentifier(), prompt_id);
  if (callback_state)
    callback_state->Invalidate();

  ScopedJNIEnv env;
  if (!env)
    return;
  ScopedJNIBrowser jbrowser(env, browser);
  if (!jbrowser)
    return;
  JNI_CALL_VOID_METHOD(env, handle_, "onDismissPermissionPrompt", "(Lorg/cef/browser/CefBrowser;JI)V", jbrowser.get(), permission_util::PromptIdToJNI(prompt_id), permission_util::PermissionResultToJNI(result));
}

void PermissionHandler::OnBeforeClose(CefRefPtr<CefBrowser> browser) {
  REQUIRE_UI_THREAD();
  if (browser)
    InvalidateBrowserCallbacks(browser->GetIdentifier());
}

void PermissionHandler::OnRenderProcessTerminated(CefRefPtr<CefBrowser> browser) {
  REQUIRE_UI_THREAD();
  if (browser)
    InvalidateBrowserCallbacks(browser->GetIdentifier());
}

void PermissionHandler::Shutdown() {
  InvalidateAllCallbacks();
}

void PermissionHandler::OnPermissionCallbackTerminal(PermissionCallbackState* callback) {
  RemoveCallback(callback);
}

bool PermissionHandler::IsValid() const {
  return handle_.get() != nullptr;
}

bool PermissionHandler::IsSameJavaHandler(JNIEnv* env, jobject handler) const {
  return env && handler && env->IsSameObject(handle_.get(), handler) == JNI_TRUE;
}

bool PermissionHandler::RegisterAndActivate(CefRefPtr<PermissionCallbackState> callback) {
  if (!callbacks_.Add(callback)) {
    callback->Abandon();
    return false;
  }
  callback->Activate(this);
  return true;
}

void PermissionHandler::RemoveCallback(PermissionCallbackState* callback) {
  callbacks_.Remove(callback);
}

CefRefPtr<PermissionCallbackState> PermissionHandler::TakePromptCallback(int browser_identifier, uint64_t prompt_id) {
  return callbacks_.TakePromptCallback(browser_identifier, prompt_id);
}

void PermissionHandler::InvalidateBrowserCallbacks(int browser_identifier) {
  callbacks_.InvalidateBrowserCallbacks(browser_identifier);
}

void PermissionHandler::InvalidateAllCallbacks() {
  callbacks_.InvalidateAllCallbacks();
}
