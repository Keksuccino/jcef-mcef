// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#ifndef JCEF_NATIVE_PERMISSION_CALLBACK_JNI_H_
#define JCEF_NATIVE_PERMISSION_CALLBACK_JNI_H_
#pragma once

#include <jni.h>

// Atomically detach a one-shot permission callback from its Java wrapper and
// release exactly the reference originally installed by ScopedJNIObject. The
// Java monitor serializes this operation with a retained worker's
// claim-and-JNI-call sequence.
jlong TakePermissionCallbackNativeRef(JNIEnv* env, jobject callback);

template <class T>
void ReleasePermissionCallbackNativeRef(JNIEnv* env, jobject callback) {
  const jlong native_ref = TakePermissionCallbackNativeRef(env, callback);
  if (native_ref != 0)
    reinterpret_cast<T*>(native_ref)->Release();
}

#endif  // JCEF_NATIVE_PERMISSION_CALLBACK_JNI_H_
