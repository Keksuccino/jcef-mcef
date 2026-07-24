// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#ifndef JCEF_NATIVE_RESOLVE_CALLBACK_H_
#define JCEF_NATIVE_RESOLVE_CALLBACK_H_
#pragma once

#include <atomic>

#include <jni.h>

#include "include/cef_request_context.h"

#include "jni_scoped_helpers.h"

// Owns the Java callback until CEF completes or abandons the request.
class ResolveCallback : public CefResolveCallback {
 public:
  ResolveCallback(JNIEnv* env, jobject callback);

  void OnResolveCompleted(cef_errorcode_t result, const std::vector<CefString>& resolved_ips) override;

 private:
  // CEF promises one completion. The atomic guard also makes duplicate or
  // racing callbacks from a mismatched runtime harmless instead of dispatching
  // user code twice.
  std::atomic<bool> completed_{false};
  ScopedJNIObjectGlobal handle_;

  IMPLEMENT_REFCOUNTING(ResolveCallback);
};

#endif  // JCEF_NATIVE_RESOLVE_CALLBACK_H_
