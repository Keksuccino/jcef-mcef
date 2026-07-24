// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "setting_observer.h"

#include "jni_scoped_helpers.h"
#include "jni_util.h"

namespace {

jobject NewJNIContentSettingType(JNIEnv* env, cef_content_setting_types_t content_type) {
  ScopedJNIClass cls(env, "org/cef/browser/CefContentSettingType");
  if (!cls)
    return nullptr;
  jmethodID method = env->GetStaticMethodID(cls, "fromValue", "(I)Lorg/cef/browser/CefContentSettingType;");
  if (!method)
    return nullptr;
  return env->CallStaticObjectMethod(cls, method, static_cast<jint>(content_type));
}

}  // namespace

SettingObserver::SettingObserver(JNIEnv* env, jobject observer) {
  handle_ = env->NewGlobalRef(observer);
  DCHECK(handle_);
}

SettingObserver::~SettingObserver() {
  Dispose();
}

void SettingObserver::OnSettingChanged(const CefString& requesting_url, const CefString& top_level_url, cef_content_setting_types_t content_type) {
  // Java callbacks may dispose their own registration. Keep this observer alive
  // until control has returned from Java even if unregistering releases CEF's
  // last reference synchronously.
  CefRefPtr<SettingObserver> self(this);
  ScopedJNIEnv env;
  if (!env)
    return;

  jobject local_handle = nullptr;
  {
    std::lock_guard<std::mutex> guard(lock_);
    if (!handle_)
      return;
    // The local reference keeps the callback target alive if dispose races this
    // in-flight UI callback. Future callbacks observe the cleared global
    // reference and return immediately.
    local_handle = env->NewLocalRef(handle_);
  }
  ScopedJNIObjectLocal observer(env, local_handle);
  if (!observer)
    return;

  ScopedJNIString requesting_url_value(env, requesting_url);
  ScopedJNIString top_level_url_value(env, top_level_url);
  ScopedJNIObjectLocal content_type_value(env, NewJNIContentSettingType(env, content_type));
  if (!content_type_value || env->ExceptionCheck())
    return;

  JNI_CALL_VOID_METHOD(env, observer, "onSettingChanged", "(Ljava/lang/String;Ljava/lang/String;Lorg/cef/browser/CefContentSettingType;)V", requesting_url_value.get(), top_level_url_value.get(), content_type_value.get());
}

void SettingObserver::Dispose() {
  ScopedJNIEnv env;
  if (!env)
    return;

  jobject old_handle = nullptr;
  {
    std::lock_guard<std::mutex> guard(lock_);
    old_handle = handle_;
    handle_ = nullptr;
  }
  if (old_handle)
    env->DeleteGlobalRef(old_handle);
}

SettingObserverRegistration::SettingObserverRegistration(CefRefPtr<CefRegistration> registration, CefRefPtr<SettingObserver> observer) : registration_(registration), observer_(observer) {}

SettingObserverRegistration::~SettingObserverRegistration() {
  // Disable Java dispatch before destroying CEF's registration. This ordering
  // remains safe even if Chromium releases the observer asynchronously or a
  // callback is already in flight.
  observer_->Dispose();
  registration_ = nullptr;
  observer_ = nullptr;
}
