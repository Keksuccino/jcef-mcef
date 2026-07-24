// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#ifndef JCEF_NATIVE_SETTING_OBSERVER_H_
#define JCEF_NATIVE_SETTING_OBSERVER_H_
#pragma once

#include <mutex>

#include <jni.h>

#include "include/cef_registration.h"
#include "include/cef_request_context.h"

// Bridges CEF setting changes to Java while a SettingObserverRegistration is
// alive.
class SettingObserver : public CefSettingObserver {
 public:
  SettingObserver(JNIEnv* env, jobject observer);
  ~SettingObserver() override;

  void OnSettingChanged(const CefString& requesting_url, const CefString& top_level_url, cef_content_setting_types_t content_type) override;

  // Stops Java callbacks and releases the global reference immediately. CEF may
  // retain this native observer briefly after its registration is destroyed, so
  // relying only on this destructor would keep arbitrary user state alive
  // longer than the public CefRegistration.
  void Dispose();

 private:
  std::mutex lock_;
  jobject handle_ = nullptr;

  IMPLEMENT_REFCOUNTING(SettingObserver);
};

// Couples the observer's Java lifetime to the existing CefRegistration
// abstraction.
class SettingObserverRegistration : public CefRegistration {
 public:
  SettingObserverRegistration(CefRefPtr<CefRegistration> registration, CefRefPtr<SettingObserver> observer);
  ~SettingObserverRegistration() override;

 private:
  CefRefPtr<CefRegistration> registration_;
  CefRefPtr<SettingObserver> observer_;

  IMPLEMENT_REFCOUNTING(SettingObserverRegistration);
};

#endif  // JCEF_NATIVE_SETTING_OBSERVER_H_
