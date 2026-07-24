// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "CefDictionaryValue_N.h"

#include "cef_value_util.h"

namespace {

const char kCefClassName[] = "CefDictionaryValue";

jobject NewJNIValue(JNIEnv* env, CefRefPtr<CefValue> value) {
  return cef_value_util::NewJNIValue(env, value, "org/cef/callback/CefValue_N",
                                     "CefValue");
}

jobject NewJNIBinaryValue(JNIEnv* env, CefRefPtr<CefBinaryValue> value) {
  return cef_value_util::NewJNIValue(
      env, value, "org/cef/callback/CefBinaryValue_N", "CefBinaryValue");
}

jobject NewJNIDictionaryValue(JNIEnv* env,
                              CefRefPtr<CefDictionaryValue> value) {
  return cef_value_util::NewJNIValue(
      env, value, "org/cef/callback/CefDictionaryValue_N", kCefClassName);
}

jobject NewJNIListValue(JNIEnv* env, CefRefPtr<CefListValue> value) {
  return cef_value_util::NewJNIValue(
      env, value, "org/cef/callback/CefListValue_N", "CefListValue");
}

bool RequireKey(JNIEnv* env, jstring key) {
  return cef_value_util::RequireString(env, key, "key must not be null");
}

}  // namespace

JNIEXPORT jobject JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1Create(JNIEnv* env, jclass cls) {
  return NewJNIDictionaryValue(env, CefDictionaryValue::Create());
}

JNIEXPORT void JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1Dispose(JNIEnv* env,
                                                       jobject obj,
                                                       jlong self) {
  SetCefForJNIObject<CefDictionaryValue>(env, obj, nullptr, kCefClassName);
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1IsValid(JNIEnv* env,
                                                       jobject obj,
                                                       jlong self) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  return value && value->IsValid() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1IsOwned(JNIEnv* env,
                                                       jobject obj,
                                                       jlong self) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  if (!cef_value_util::RequireValid(env, value))
    return JNI_FALSE;
  return value->IsOwned() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1IsReadOnly(JNIEnv* env,
                                                          jobject obj,
                                                          jlong self) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  if (!cef_value_util::RequireValid(env, value))
    return JNI_TRUE;
  return value->IsReadOnly() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1IsSame(JNIEnv* env,
                                                      jobject obj,
                                                      jlong self,
                                                      jlong that) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  CefRefPtr<CefDictionaryValue> other =
      cef_value_util::GetSelf<CefDictionaryValue>(that);
  if (!cef_value_util::RequireValid(env, value) ||
      !cef_value_util::RequireValid(env, other)) {
    return JNI_FALSE;
  }
  return value->IsSame(other) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1IsEqual(JNIEnv* env,
                                                       jobject obj,
                                                       jlong self,
                                                       jlong that) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  CefRefPtr<CefDictionaryValue> other =
      cef_value_util::GetSelf<CefDictionaryValue>(that);
  if (!cef_value_util::RequireValid(env, value) ||
      !cef_value_util::RequireValid(env, other)) {
    return JNI_FALSE;
  }
  return value->IsEqual(other) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL Java_org_cef_callback_CefDictionaryValue_1N_N_1Copy(
    JNIEnv* env,
    jobject obj,
    jlong self,
    jboolean exclude_empty_children) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  if (!cef_value_util::RequireValid(env, value))
    return nullptr;
  return NewJNIDictionaryValue(env,
                               value->Copy(exclude_empty_children == JNI_TRUE));
}

JNIEXPORT jlong JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1GetSize(JNIEnv* env,
                                                       jobject obj,
                                                       jlong self) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  if (!cef_value_util::RequireValid(env, value))
    return 0;
  return cef_value_util::FromSize(env, value->GetSize());
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1Clear(JNIEnv* env,
                                                     jobject obj,
                                                     jlong self) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  if (!cef_value_util::RequireValid(env, value))
    return JNI_FALSE;
  return value->Clear() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1HasKey(JNIEnv* env,
                                                      jobject obj,
                                                      jlong self,
                                                      jstring key) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  if (!cef_value_util::RequireValid(env, value) || !RequireKey(env, key))
    return JNI_FALSE;
  return value->HasKey(GetJNIString(env, key)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1GetKeys(JNIEnv* env,
                                                       jobject obj,
                                                       jlong self) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  if (!cef_value_util::RequireValid(env, value))
    return nullptr;
  CefDictionaryValue::KeyList keys;
  if (!value->GetKeys(keys)) {
    cef_value_util::ThrowJavaException(env, "java/lang/IllegalStateException",
                                       "CEF failed to read dictionary keys");
    return nullptr;
  }
  return cef_value_util::NewJNIStringArray(env, keys);
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1Remove(JNIEnv* env,
                                                      jobject obj,
                                                      jlong self,
                                                      jstring key) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  if (!cef_value_util::RequireValid(env, value) || !RequireKey(env, key))
    return JNI_FALSE;
  return value->Remove(GetJNIString(env, key)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1GetType(JNIEnv* env,
                                                       jobject obj,
                                                       jlong self,
                                                       jstring key) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  if (!cef_value_util::RequireValid(env, value) || !RequireKey(env, key))
    return VTYPE_INVALID;
  return static_cast<jint>(value->GetType(GetJNIString(env, key)));
}

JNIEXPORT jobject JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1GetValue(JNIEnv* env,
                                                        jobject obj,
                                                        jlong self,
                                                        jstring key) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  if (!cef_value_util::RequireValid(env, value) || !RequireKey(env, key))
    return nullptr;
  return NewJNIValue(env, value->GetValue(GetJNIString(env, key)));
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1GetBool(JNIEnv* env,
                                                       jobject obj,
                                                       jlong self,
                                                       jstring key) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  if (!cef_value_util::RequireValid(env, value) || !RequireKey(env, key))
    return JNI_FALSE;
  return value->GetBool(GetJNIString(env, key)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1GetInt(JNIEnv* env,
                                                      jobject obj,
                                                      jlong self,
                                                      jstring key) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  if (!cef_value_util::RequireValid(env, value) || !RequireKey(env, key))
    return 0;
  return static_cast<jint>(value->GetInt(GetJNIString(env, key)));
}

JNIEXPORT jdouble JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1GetDouble(JNIEnv* env,
                                                         jobject obj,
                                                         jlong self,
                                                         jstring key) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  if (!cef_value_util::RequireValid(env, value) || !RequireKey(env, key))
    return 0.0;
  return static_cast<jdouble>(value->GetDouble(GetJNIString(env, key)));
}

JNIEXPORT jstring JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1GetString(JNIEnv* env,
                                                         jobject obj,
                                                         jlong self,
                                                         jstring key) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  if (!cef_value_util::RequireValid(env, value) || !RequireKey(env, key))
    return nullptr;
  return NewJNIString(env, value->GetString(GetJNIString(env, key)));
}

JNIEXPORT jobject JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1GetBinary(JNIEnv* env,
                                                         jobject obj,
                                                         jlong self,
                                                         jstring key) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  if (!cef_value_util::RequireValid(env, value) || !RequireKey(env, key))
    return nullptr;
  return NewJNIBinaryValue(env, value->GetBinary(GetJNIString(env, key)));
}

JNIEXPORT jobject JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1GetDictionary(JNIEnv* env,
                                                             jobject obj,
                                                             jlong self,
                                                             jstring key) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  if (!cef_value_util::RequireValid(env, value) || !RequireKey(env, key))
    return nullptr;
  return NewJNIDictionaryValue(env,
                               value->GetDictionary(GetJNIString(env, key)));
}

JNIEXPORT jobject JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1GetList(JNIEnv* env,
                                                       jobject obj,
                                                       jlong self,
                                                       jstring key) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  if (!cef_value_util::RequireValid(env, value) || !RequireKey(env, key))
    return nullptr;
  return NewJNIListValue(env, value->GetList(GetJNIString(env, key)));
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1SetValue(JNIEnv* env,
                                                        jobject obj,
                                                        jlong self,
                                                        jstring key,
                                                        jlong child) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  CefRefPtr<CefValue> child_value = cef_value_util::GetSelf<CefValue>(child);
  if (!cef_value_util::RequireValid(env, value) || !RequireKey(env, key) ||
      !cef_value_util::RequireValid(env, child_value)) {
    return JNI_FALSE;
  }
  return value->SetValue(GetJNIString(env, key), child_value) ? JNI_TRUE
                                                              : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1SetNull(JNIEnv* env,
                                                       jobject obj,
                                                       jlong self,
                                                       jstring key) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  if (!cef_value_util::RequireValid(env, value) || !RequireKey(env, key))
    return JNI_FALSE;
  return value->SetNull(GetJNIString(env, key)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1SetBool(JNIEnv* env,
                                                       jobject obj,
                                                       jlong self,
                                                       jstring key,
                                                       jboolean child) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  if (!cef_value_util::RequireValid(env, value) || !RequireKey(env, key))
    return JNI_FALSE;
  return value->SetBool(GetJNIString(env, key), child == JNI_TRUE) ? JNI_TRUE
                                                                   : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1SetInt(JNIEnv* env,
                                                      jobject obj,
                                                      jlong self,
                                                      jstring key,
                                                      jint child) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  if (!cef_value_util::RequireValid(env, value) || !RequireKey(env, key))
    return JNI_FALSE;
  return value->SetInt(GetJNIString(env, key), static_cast<int>(child))
             ? JNI_TRUE
             : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1SetDouble(JNIEnv* env,
                                                         jobject obj,
                                                         jlong self,
                                                         jstring key,
                                                         jdouble child) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  if (!cef_value_util::RequireValid(env, value) || !RequireKey(env, key))
    return JNI_FALSE;
  return value->SetDouble(GetJNIString(env, key), static_cast<double>(child))
             ? JNI_TRUE
             : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1SetString(JNIEnv* env,
                                                         jobject obj,
                                                         jlong self,
                                                         jstring key,
                                                         jstring child) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  if (!cef_value_util::RequireValid(env, value) || !RequireKey(env, key) ||
      !cef_value_util::RequireString(env, child, "value must not be null")) {
    return JNI_FALSE;
  }
  return value->SetString(GetJNIString(env, key), GetJNIString(env, child))
             ? JNI_TRUE
             : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1SetBinary(JNIEnv* env,
                                                         jobject obj,
                                                         jlong self,
                                                         jstring key,
                                                         jlong child) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  CefRefPtr<CefBinaryValue> child_value =
      cef_value_util::GetSelf<CefBinaryValue>(child);
  if (!cef_value_util::RequireValid(env, value) || !RequireKey(env, key) ||
      !cef_value_util::RequireValid(env, child_value)) {
    return JNI_FALSE;
  }
  return value->SetBinary(GetJNIString(env, key), child_value) ? JNI_TRUE
                                                               : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1SetDictionary(JNIEnv* env,
                                                             jobject obj,
                                                             jlong self,
                                                             jstring key,
                                                             jlong child) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  CefRefPtr<CefDictionaryValue> child_value =
      cef_value_util::GetSelf<CefDictionaryValue>(child);
  if (!cef_value_util::RequireValid(env, value) || !RequireKey(env, key) ||
      !cef_value_util::RequireValid(env, child_value)) {
    return JNI_FALSE;
  }
  return value->SetDictionary(GetJNIString(env, key), child_value) ? JNI_TRUE
                                                                   : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefDictionaryValue_1N_N_1SetList(JNIEnv* env,
                                                       jobject obj,
                                                       jlong self,
                                                       jstring key,
                                                       jlong child) {
  CefRefPtr<CefDictionaryValue> value =
      cef_value_util::GetSelf<CefDictionaryValue>(self);
  CefRefPtr<CefListValue> child_value =
      cef_value_util::GetSelf<CefListValue>(child);
  if (!cef_value_util::RequireValid(env, value) || !RequireKey(env, key) ||
      !cef_value_util::RequireValid(env, child_value)) {
    return JNI_FALSE;
  }
  return value->SetList(GetJNIString(env, key), child_value) ? JNI_TRUE
                                                             : JNI_FALSE;
}
