// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "permission_callback_jni.h"

#include "jni_scoped_helpers.h"

jlong TakePermissionCallbackNativeRef(JNIEnv* env, jobject callback) {
  // ScopedJNIString can leave an allocation exception pending before a handler
  // reaches its wrapper-failure path. JNI method lookup/calls are suppressed
  // with a pending exception, so clear it before atomically detaching the
  // native reference.
  if (env && env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
  jlong native_ref = 0;
  JNI_CALL_METHOD(env, callback, "takeNativeRef", "()J", Long, native_ref);
  return native_ref;
}
