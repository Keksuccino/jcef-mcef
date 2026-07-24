// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "CefBinaryValue_N.h"

#include <limits>

#include "cef_value_util.h"

namespace {

const char kCefClassName[] = "CefBinaryValue";

jobject NewJNIBinaryValue(JNIEnv* env, CefRefPtr<CefBinaryValue> value) {
  return cef_value_util::NewJNIValue(
      env, value, "org/cef/callback/CefBinaryValue_N", kCefClassName);
}

bool ValidateArrayRange(JNIEnv* env,
                        jbyteArray data,
                        jint offset,
                        jint length) {
  if (!data) {
    cef_value_util::ThrowJavaException(env, "java/lang/NullPointerException",
                                       "data must not be null");
    return false;
  }
  const jsize array_length = env->GetArrayLength(data);
  if (offset < 0 || length < 0 || offset > array_length ||
      length > array_length - offset) {
    cef_value_util::ThrowJavaException(
        env, "java/lang/IndexOutOfBoundsException",
        "byte array offset and length are outside the array");
    return false;
  }
  return true;
}

}  // namespace

JNIEXPORT jobject JNICALL
Java_org_cef_callback_CefBinaryValue_1N_N_1Create(JNIEnv* env,
                                                  jclass cls,
                                                  jbyteArray data,
                                                  jint offset,
                                                  jint length) {
  if (!ValidateArrayRange(env, data, offset, length))
    return nullptr;
  if (length == 0) {
    cef_value_util::ThrowJavaException(env,
                                       "java/lang/IllegalArgumentException",
                                       "CEF binary values cannot be empty");
    return nullptr;
  }

  jbyte* bytes = env->GetByteArrayElements(data, nullptr);
  if (!bytes)
    return nullptr;
  CefRefPtr<CefBinaryValue> value =
      CefBinaryValue::Create(bytes + offset, static_cast<size_t>(length));
  env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
  if (!value) {
    cef_value_util::ThrowJavaException(env, "java/lang/IllegalStateException",
                                       "CEF failed to create a binary value");
    return nullptr;
  }
  return NewJNIBinaryValue(env, value);
}

JNIEXPORT void JNICALL
Java_org_cef_callback_CefBinaryValue_1N_N_1Dispose(JNIEnv* env,
                                                   jobject obj,
                                                   jlong self) {
  SetCefForJNIObject<CefBinaryValue>(env, obj, nullptr, kCefClassName);
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefBinaryValue_1N_N_1IsValid(JNIEnv* env,
                                                   jobject obj,
                                                   jlong self) {
  CefRefPtr<CefBinaryValue> value =
      cef_value_util::GetSelf<CefBinaryValue>(self);
  return value && value->IsValid() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefBinaryValue_1N_N_1IsOwned(JNIEnv* env,
                                                   jobject obj,
                                                   jlong self) {
  CefRefPtr<CefBinaryValue> value =
      cef_value_util::GetSelf<CefBinaryValue>(self);
  if (!cef_value_util::RequireValid(env, value))
    return JNI_FALSE;
  return value->IsOwned() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefBinaryValue_1N_N_1IsSame(JNIEnv* env,
                                                  jobject obj,
                                                  jlong self,
                                                  jlong that) {
  CefRefPtr<CefBinaryValue> value =
      cef_value_util::GetSelf<CefBinaryValue>(self);
  CefRefPtr<CefBinaryValue> other =
      cef_value_util::GetSelf<CefBinaryValue>(that);
  if (!cef_value_util::RequireValid(env, value) ||
      !cef_value_util::RequireValid(env, other)) {
    return JNI_FALSE;
  }
  return value->IsSame(other) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefBinaryValue_1N_N_1IsEqual(JNIEnv* env,
                                                   jobject obj,
                                                   jlong self,
                                                   jlong that) {
  CefRefPtr<CefBinaryValue> value =
      cef_value_util::GetSelf<CefBinaryValue>(self);
  CefRefPtr<CefBinaryValue> other =
      cef_value_util::GetSelf<CefBinaryValue>(that);
  if (!cef_value_util::RequireValid(env, value) ||
      !cef_value_util::RequireValid(env, other)) {
    return JNI_FALSE;
  }
  return value->IsEqual(other) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL
Java_org_cef_callback_CefBinaryValue_1N_N_1Copy(JNIEnv* env,
                                                jobject obj,
                                                jlong self) {
  CefRefPtr<CefBinaryValue> value =
      cef_value_util::GetSelf<CefBinaryValue>(self);
  if (!cef_value_util::RequireValid(env, value))
    return nullptr;
  return NewJNIBinaryValue(env, value->Copy());
}

JNIEXPORT jlong JNICALL
Java_org_cef_callback_CefBinaryValue_1N_N_1GetSize(JNIEnv* env,
                                                   jobject obj,
                                                   jlong self) {
  CefRefPtr<CefBinaryValue> value =
      cef_value_util::GetSelf<CefBinaryValue>(self);
  if (!cef_value_util::RequireValid(env, value))
    return 0;
  return cef_value_util::FromSize(env, value->GetSize());
}

JNIEXPORT jint JNICALL
Java_org_cef_callback_CefBinaryValue_1N_N_1GetData(JNIEnv* env,
                                                   jobject obj,
                                                   jlong self,
                                                   jbyteArray buffer,
                                                   jint buffer_offset,
                                                   jint length,
                                                   jlong data_offset) {
  CefRefPtr<CefBinaryValue> value =
      cef_value_util::GetSelf<CefBinaryValue>(self);
  if (!cef_value_util::RequireValid(env, value) ||
      !ValidateArrayRange(env, buffer, buffer_offset, length)) {
    return 0;
  }
  size_t native_data_offset = 0;
  if (!cef_value_util::ToSize(env, data_offset, "dataOffset",
                              &native_data_offset)) {
    return 0;
  }
  if (length == 0)
    return 0;

  jbyte* bytes = env->GetByteArrayElements(buffer, nullptr);
  if (!bytes)
    return 0;
  const size_t read = value->GetData(
      bytes + buffer_offset, static_cast<size_t>(length), native_data_offset);
  env->ReleaseByteArrayElements(buffer, bytes, 0);
  if (read > static_cast<size_t>(std::numeric_limits<jint>::max())) {
    cef_value_util::ThrowJavaException(
        env, "java/lang/ArithmeticException",
        "CEF binary read count exceeds Java int range");
    return 0;
  }
  return static_cast<jint>(read);
}
