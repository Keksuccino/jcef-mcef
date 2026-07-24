// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#ifndef JCEF_NATIVE_FIND_HANDLER_H_
#define JCEF_NATIVE_FIND_HANDLER_H_
#pragma once

#include <jni.h>

#include "include/cef_find_handler.h"

#include "jni_scoped_helpers.h"

// CefFindHandler implementation that forwards UI-thread callbacks to Java.
class FindHandler : public CefFindHandler {
 public:
  FindHandler(JNIEnv* env, jobject handler);

  // CefFindHandler methods:
  void OnFindResult(CefRefPtr<CefBrowser> browser, int identifier, int count, const CefRect& selection_rect, int active_match_ordinal, bool final_update) override;

 protected:
  // The global reference intentionally keeps the Java CefClient relay alive
  // while CEF retains this handler. CefClient terminal cleanup detaches the
  // Java-owned native reference after all browsers close, breaking the
  // reciprocal ownership cycle without disrupting dynamic delegate replacement.
  ScopedJNIObjectGlobal handle_;

  IMPLEMENT_REFCOUNTING(FindHandler);
};

#endif  // JCEF_NATIVE_FIND_HANDLER_H_
