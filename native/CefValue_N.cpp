// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "CefValue_N.h"

#include "cef_value_util.h"

namespace {

const char kCefClassName[] = "CefValue";

jobject NewJNIValue(JNIEnv* env, CefRefPtr<CefValue> value) {
  return cef_value_util::NewJNIValue(env, value, "org/cef/callback/CefValue_N",
                                     kCefClassName);
}

jobject NewJNIBinaryValue(JNIEnv* env, CefRefPtr<CefBinaryValue> value) {
  return cef_value_util::NewJNIValue(
      env, value, "org/cef/callback/CefBinaryValue_N", "CefBinaryValue");
}

jobject NewJNIDictionaryValue(JNIEnv* env,
                              CefRefPtr<CefDictionaryValue> value) {
  return cef_value_util::NewJNIValue(env, value,
                                     "org/cef/callback/CefDictionaryValue_N",
                                     "CefDictionaryValue");
}

jobject NewJNIListValue(JNIEnv* env, CefRefPtr<CefListValue> value) {
  return cef_value_util::NewJNIValue(
      env, value, "org/cef/callback/CefListValue_N", "CefListValue");
}

}  // namespace

JNIEXPORT jobject JNICALL
Java_org_cef_callback_CefValue_1N_N_1Create(JNIEnv* env, jclass cls) {
  return NewJNIValue(env, CefValue::Create());
}

JNIEXPORT void JNICALL
Java_org_cef_callback_CefValue_1N_N_1Dispose(JNIEnv* env,
                                             jobject obj,
                                             jlong self) {
  SetCefForJNIObject<CefValue>(env, obj, nullptr, kCefClassName);
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefValue_1N_N_1IsValid(JNIEnv* env,
                                             jobject obj,
                                             jlong self) {
  CefRefPtr<CefValue> value = cef_value_util::GetSelf<CefValue>(self);
  return value && value->IsValid() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefValue_1N_N_1IsOwned(JNIEnv* env,
                                             jobject obj,
                                             jlong self) {
  CefRefPtr<CefValue> value = cef_value_util::GetSelf<CefValue>(self);
  if (!cef_value_util::RequireValid(env, value))
    return JNI_FALSE;
  return value->IsOwned() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefValue_1N_N_1IsReadOnly(JNIEnv* env,
                                                jobject obj,
                                                jlong self) {
  CefRefPtr<CefValue> value = cef_value_util::GetSelf<CefValue>(self);
  if (!cef_value_util::RequireValid(env, value))
    return JNI_TRUE;
  return value->IsReadOnly() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefValue_1N_N_1IsSame(JNIEnv* env,
                                            jobject obj,
                                            jlong self,
                                            jlong that) {
  CefRefPtr<CefValue> value = cef_value_util::GetSelf<CefValue>(self);
  CefRefPtr<CefValue> other = cef_value_util::GetSelf<CefValue>(that);
  if (!cef_value_util::RequireValid(env, value) ||
      !cef_value_util::RequireValid(env, other)) {
    return JNI_FALSE;
  }
  return value->IsSame(other) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefValue_1N_N_1IsEqual(JNIEnv* env,
                                             jobject obj,
                                             jlong self,
                                             jlong that) {
  CefRefPtr<CefValue> value = cef_value_util::GetSelf<CefValue>(self);
  CefRefPtr<CefValue> other = cef_value_util::GetSelf<CefValue>(that);
  if (!cef_value_util::RequireValid(env, value) ||
      !cef_value_util::RequireValid(env, other)) {
    return JNI_FALSE;
  }
  return value->IsEqual(other) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL
Java_org_cef_callback_CefValue_1N_N_1Copy(JNIEnv* env,
                                          jobject obj,
                                          jlong self) {
  CefRefPtr<CefValue> value = cef_value_util::GetSelf<CefValue>(self);
  if (!cef_value_util::RequireValid(env, value))
    return nullptr;
  return NewJNIValue(env, value->Copy());
}

JNIEXPORT jint JNICALL
Java_org_cef_callback_CefValue_1N_N_1GetType(JNIEnv* env,
                                             jobject obj,
                                             jlong self) {
  CefRefPtr<CefValue> value = cef_value_util::GetSelf<CefValue>(self);
  if (!cef_value_util::RequireValid(env, value))
    return VTYPE_INVALID;
  return static_cast<jint>(value->GetType());
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefValue_1N_N_1GetBool(JNIEnv* env,
                                             jobject obj,
                                             jlong self) {
  CefRefPtr<CefValue> value = cef_value_util::GetSelf<CefValue>(self);
  if (!cef_value_util::RequireValid(env, value))
    return JNI_FALSE;
  return value->GetBool() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_org_cef_callback_CefValue_1N_N_1GetInt(JNIEnv* env,
                                                                   jobject obj,
                                                                   jlong self) {
  CefRefPtr<CefValue> value = cef_value_util::GetSelf<CefValue>(self);
  if (!cef_value_util::RequireValid(env, value))
    return 0;
  return static_cast<jint>(value->GetInt());
}

JNIEXPORT jdouble JNICALL
Java_org_cef_callback_CefValue_1N_N_1GetDouble(JNIEnv* env,
                                               jobject obj,
                                               jlong self) {
  CefRefPtr<CefValue> value = cef_value_util::GetSelf<CefValue>(self);
  if (!cef_value_util::RequireValid(env, value))
    return 0.0;
  return static_cast<jdouble>(value->GetDouble());
}

JNIEXPORT jstring JNICALL
Java_org_cef_callback_CefValue_1N_N_1GetString(JNIEnv* env,
                                               jobject obj,
                                               jlong self) {
  CefRefPtr<CefValue> value = cef_value_util::GetSelf<CefValue>(self);
  if (!cef_value_util::RequireValid(env, value))
    return nullptr;
  return NewJNIString(env, value->GetString());
}

JNIEXPORT jobject JNICALL
Java_org_cef_callback_CefValue_1N_N_1GetBinary(JNIEnv* env,
                                               jobject obj,
                                               jlong self) {
  CefRefPtr<CefValue> value = cef_value_util::GetSelf<CefValue>(self);
  if (!cef_value_util::RequireValid(env, value))
    return nullptr;
  return NewJNIBinaryValue(env, value->GetBinary());
}

JNIEXPORT jobject JNICALL
Java_org_cef_callback_CefValue_1N_N_1GetDictionary(JNIEnv* env,
                                                   jobject obj,
                                                   jlong self) {
  CefRefPtr<CefValue> value = cef_value_util::GetSelf<CefValue>(self);
  if (!cef_value_util::RequireValid(env, value))
    return nullptr;
  return NewJNIDictionaryValue(env, value->GetDictionary());
}

JNIEXPORT jobject JNICALL
Java_org_cef_callback_CefValue_1N_N_1GetList(JNIEnv* env,
                                             jobject obj,
                                             jlong self) {
  CefRefPtr<CefValue> value = cef_value_util::GetSelf<CefValue>(self);
  if (!cef_value_util::RequireValid(env, value))
    return nullptr;
  return NewJNIListValue(env, value->GetList());
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefValue_1N_N_1SetNull(JNIEnv* env,
                                             jobject obj,
                                             jlong self) {
  CefRefPtr<CefValue> value = cef_value_util::GetSelf<CefValue>(self);
  if (!cef_value_util::RequirePresent(env, value))
    return JNI_FALSE;
  return value->SetNull() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefValue_1N_N_1SetBool(JNIEnv* env,
                                             jobject obj,
                                             jlong self,
                                             jboolean bool_value) {
  CefRefPtr<CefValue> value = cef_value_util::GetSelf<CefValue>(self);
  if (!cef_value_util::RequirePresent(env, value))
    return JNI_FALSE;
  return value->SetBool(bool_value == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefValue_1N_N_1SetInt(JNIEnv* env,
                                            jobject obj,
                                            jlong self,
                                            jint int_value) {
  CefRefPtr<CefValue> value = cef_value_util::GetSelf<CefValue>(self);
  if (!cef_value_util::RequirePresent(env, value))
    return JNI_FALSE;
  return value->SetInt(static_cast<int>(int_value)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefValue_1N_N_1SetDouble(JNIEnv* env,
                                               jobject obj,
                                               jlong self,
                                               jdouble double_value) {
  CefRefPtr<CefValue> value = cef_value_util::GetSelf<CefValue>(self);
  if (!cef_value_util::RequirePresent(env, value))
    return JNI_FALSE;
  return value->SetDouble(static_cast<double>(double_value)) ? JNI_TRUE
                                                             : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefValue_1N_N_1SetString(JNIEnv* env,
                                               jobject obj,
                                               jlong self,
                                               jstring string_value) {
  CefRefPtr<CefValue> value = cef_value_util::GetSelf<CefValue>(self);
  if (!cef_value_util::RequirePresent(env, value) ||
      !cef_value_util::RequireString(env, string_value,
                                     "value must not be null")) {
    return JNI_FALSE;
  }
  return value->SetString(GetJNIString(env, string_value)) ? JNI_TRUE
                                                           : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefValue_1N_N_1SetBinary(JNIEnv* env,
                                               jobject obj,
                                               jlong self,
                                               jlong binary_value) {
  CefRefPtr<CefValue> value = cef_value_util::GetSelf<CefValue>(self);
  CefRefPtr<CefBinaryValue> binary =
      cef_value_util::GetSelf<CefBinaryValue>(binary_value);
  if (!cef_value_util::RequirePresent(env, value) ||
      !cef_value_util::RequireValid(env, binary)) {
    return JNI_FALSE;
  }
  return value->SetBinary(binary) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefValue_1N_N_1SetDictionary(JNIEnv* env,
                                                   jobject obj,
                                                   jlong self,
                                                   jlong dictionary_value) {
  CefRefPtr<CefValue> value = cef_value_util::GetSelf<CefValue>(self);
  CefRefPtr<CefDictionaryValue> dictionary =
      cef_value_util::GetSelf<CefDictionaryValue>(dictionary_value);
  if (!cef_value_util::RequirePresent(env, value) ||
      !cef_value_util::RequireValid(env, dictionary)) {
    return JNI_FALSE;
  }
  return value->SetDictionary(dictionary) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefValue_1N_N_1SetList(JNIEnv* env,
                                             jobject obj,
                                             jlong self,
                                             jlong list_value) {
  CefRefPtr<CefValue> value = cef_value_util::GetSelf<CefValue>(self);
  CefRefPtr<CefListValue> list =
      cef_value_util::GetSelf<CefListValue>(list_value);
  if (!cef_value_util::RequirePresent(env, value) ||
      !cef_value_util::RequireValid(env, list)) {
    return JNI_FALSE;
  }
  return value->SetList(list) ? JNI_TRUE : JNI_FALSE;
}
