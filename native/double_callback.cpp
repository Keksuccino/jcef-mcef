// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "double_callback.h"

#include "jni_scoped_helpers.h"
#include "jni_util.h"

DoubleCallback::DoubleCallback(JNIEnv* env, jobject jcallback) : handle_(env, jcallback) {}

void DoubleCallback::onComplete(bool success, double value) {
  ScopedJNIEnv env;
  if (!env)
    return;

  JNI_CALL_VOID_METHOD(env, handle_, "onComplete", "(ZD)V", success ? JNI_TRUE : JNI_FALSE, (jdouble)value);
}
