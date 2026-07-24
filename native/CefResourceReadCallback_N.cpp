// Copyright (c) 2024 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "CefResourceReadCallback_N.h"
#include "include/cef_resource_handler.h"
#include "jni_scoped_helpers.h"

namespace {

CefRefPtr<CefResourceReadCallback> GetSelf(jlong self) {
  return reinterpret_cast<CefResourceReadCallback*>(self);
}

void ClearSelf(JNIEnv* env, jobject obj) {
  // Clear the reference added in ResourceHandler.
  SetCefForJNIObject<CefResourceReadCallback>(env, obj, nullptr,
                                              "CefResourceReadCallback");
}

}  // namespace

JNIEXPORT void JNICALL
Java_org_cef_callback_CefResourceReadCallback_1N_N_1Continue(
    JNIEnv* env,
    jobject obj,
    jlong self,
    jint bytes_read,
    jlong nativeBufferRef,
    jbyteArray javaBuffer) {
  CefRefPtr<CefResourceReadCallback> callback = GetSelf(self);
  if (!callback)
    return;

  // Invalidate before invoking CEF so a reentrant or racing continuation cannot
  // consume the same native data_out pointer twice. The local CefRefPtr keeps
  // the callback alive for this call.
  ClearSelf(env, obj);

  void* data_out = reinterpret_cast<void*>(nativeBufferRef);

  if (bytes_read > 0) {
    if (!javaBuffer || !data_out) {
      callback->Continue(ERR_FAILED);
      return;
    }
    jsize max_bytes = env->GetArrayLength(javaBuffer);
    if (bytes_read > max_bytes) {
      callback->Continue(ERR_FAILED);
      return;
    }
    jbyte* jbyte = env->GetByteArrayElements(javaBuffer, nullptr);
    if (jbyte) {
      memmove(data_out, jbyte, bytes_read);
      env->ReleaseByteArrayElements(javaBuffer, jbyte, JNI_ABORT);
    } else {
      if (env->ExceptionCheck())
        env->ExceptionClear();
      callback->Continue(ERR_FAILED);
      return;
    }
  }

  callback->Continue(bytes_read);
}
