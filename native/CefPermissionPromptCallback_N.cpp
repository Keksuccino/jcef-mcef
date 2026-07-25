// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "CefPermissionPromptCallback_N.h"

#include "jni_scoped_helpers.h"
#include "permission_callback_jni.h"
#include "permission_callback_state.h"
#include "permission_util.h"

namespace {

CefRefPtr<PermissionPromptCallbackState> GetSelf(jlong self) {
  return reinterpret_cast<PermissionPromptCallbackState*>(self);
}

void ClearSelf(JNIEnv* env, jobject obj) {
  ReleasePermissionCallbackNativeRef<PermissionPromptCallbackState>(env, obj);
}

}  // namespace

JNIEXPORT void JNICALL Java_org_cef_callback_CefPermissionPromptCallback_1N_N_1Continue(JNIEnv* env, jobject obj, jlong self, jint result) {
  CefRefPtr<PermissionPromptCallbackState> callback = GetSelf(self);
  if (!callback) return;
  callback->Continue(permission_util::PermissionResultFromJNI(result));
  ClearSelf(env, obj);
}
