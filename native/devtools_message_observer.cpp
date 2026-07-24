// Copyright (c) 2024 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "devtools_message_observer.h"

#include <initializer_list>

#include "jni_util.h"

namespace {

std::string CopyOptionalBuffer(const void* data, size_t size) {
  if (!data || size == 0)
    return std::string();
  return std::string(static_cast<const char*>(data), size);
}

bool ValidateCallbackArguments(JNIEnv* env, std::initializer_list<jobject> arguments) {
  bool valid = true;
  for (jobject argument : arguments) {
    if (!argument)
      valid = false;
  }
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
    return false;
  }
  return valid;
}

}  // namespace

DevToolsMessageObserver::DevToolsMessageObserver(JNIEnv* env, jobject observer) : handle_(env, observer) {}

void DevToolsMessageObserver::OnDevToolsMethodResult(CefRefPtr<CefBrowser> browser, int message_id, bool success, const void* result, size_t result_size) {
  ScopedJNIEnv env;
  if (!env)
    return;

  ScopedJNIBrowser jbrowser(env, browser);
  std::string strResult = CopyOptionalBuffer(result, result_size);
  ScopedJNIObjectLocal jresult(env, NewJNIString(env, strResult));
  if (!ValidateCallbackArguments(env, {jbrowser.get(), jresult.get()}))
    return;
  JNI_CALL_VOID_METHOD(env, handle_, "onDevToolsMethodResult", "(Lorg/cef/browser/CefBrowser;IZLjava/lang/String;)V", jbrowser.get(), message_id, success ? JNI_TRUE : JNI_FALSE, jresult.get());
}

void DevToolsMessageObserver::OnDevToolsEvent(CefRefPtr<CefBrowser> browser, const CefString& method, const void* params, size_t params_size) {
  ScopedJNIEnv env;
  if (!env)
    return;
  ScopedJNIBrowser jbrowser(env, browser);

  std::string strParams = CopyOptionalBuffer(params, params_size);
  ScopedJNIObjectLocal jmethod(env, NewJNIString(env, method));
  ScopedJNIObjectLocal jparams(env, NewJNIString(env, strParams));
  if (!ValidateCallbackArguments(env, {jbrowser.get(), jmethod.get(), jparams.get()}))
    return;
  JNI_CALL_VOID_METHOD(env, handle_, "onDevToolsEvent", "(Lorg/cef/browser/CefBrowser;Ljava/lang/String;Ljava/lang/String;)V", jbrowser.get(), jmethod.get(), jparams.get());
}

void DevToolsMessageObserver::OnDevToolsAgentAttached(CefRefPtr<CefBrowser> browser) {
  ScopedJNIEnv env;
  if (!env)
    return;

  ScopedJNIBrowser jbrowser(env, browser);
  JNI_CALL_VOID_METHOD(env, handle_, "onDevToolsAgentAttached", "(Lorg/cef/browser/CefBrowser;)V", jbrowser.get());
}

void DevToolsMessageObserver::OnDevToolsAgentDetached(CefRefPtr<CefBrowser> browser) {
  ScopedJNIEnv env;
  if (!env)
    return;

  ScopedJNIBrowser jbrowser(env, browser);
  JNI_CALL_VOID_METHOD(env, handle_, "onDevToolsAgentDetached", "(Lorg/cef/browser/CefBrowser;)V", jbrowser.get());
}
