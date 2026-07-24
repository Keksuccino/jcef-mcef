// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "find_handler.h"

#include "jni_util.h"

namespace {

constexpr char kOnFindResultSignature[] =
    "(Lorg/cef/browser/CefBrowser;IILjava/awt/Rectangle;IZ)V";

}  // namespace

FindHandler::FindHandler(JNIEnv* env, jobject handler) : handle_(env, handler) {}

void FindHandler::OnFindResult(CefRefPtr<CefBrowser> browser, int identifier, int count, const CefRect& selection_rect, int active_match_ordinal, bool final_update) {
  ScopedJNIEnv env;
  if (!env)
    return;

  ScopedJNIBrowser jbrowser(env, browser);
  if (!jbrowser)
    return;

  ScopedJNIObjectLocal jselection_rect(env, NewJNIRect(env, selection_rect));
  if (!jselection_rect)
    return;

  JNI_CALL_VOID_METHOD(env, handle_, "onFindResult", kOnFindResultSignature, jbrowser.get(), identifier, count, jselection_rect.get(), active_match_ordinal, static_cast<jboolean>(final_update));
}
