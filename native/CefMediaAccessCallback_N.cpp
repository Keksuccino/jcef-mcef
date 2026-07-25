// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "CefMediaAccessCallback_N.h"

#include "jni_scoped_helpers.h"
#include "permission_callback_jni.h"
#include "permission_callback_state.h"
#include "permission_util.h"

namespace {

CefRefPtr<MediaAccessCallbackState> GetSelf(jlong self) {
  return reinterpret_cast<MediaAccessCallbackState*>(self);
}

void ClearSelf(JNIEnv* env, jobject obj) {
  ReleasePermissionCallbackNativeRef<MediaAccessCallbackState>(env, obj);
}

}  // namespace

JNIEXPORT void JNICALL Java_org_cef_callback_CefMediaAccessCallback_1N_N_1Continue(JNIEnv* env, jobject obj, jlong self, jint allowed_permissions) {
  CefRefPtr<MediaAccessCallbackState> callback = GetSelf(self);
  if (!callback) return;
  callback->Continue(permission_util::PermissionMaskFromJNI(allowed_permissions));
  ClearSelf(env, obj);
}

JNIEXPORT void JNICALL Java_org_cef_callback_CefMediaAccessCallback_1N_N_1Cancel(JNIEnv* env, jobject obj, jlong self) {
  CefRefPtr<MediaAccessCallbackState> callback = GetSelf(self);
  if (!callback) return;
  callback->Cancel();
  ClearSelf(env, obj);
}
