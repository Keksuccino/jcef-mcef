// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "CefListValue_N.h"

#include "cef_value_util.h"

namespace {

const char kCefClassName[] = "CefListValue";

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
  return cef_value_util::NewJNIValue(env, value,
                                     "org/cef/callback/CefDictionaryValue_N",
                                     "CefDictionaryValue");
}

jobject NewJNIListValue(JNIEnv* env, CefRefPtr<CefListValue> value) {
  return cef_value_util::NewJNIValue(
      env, value, "org/cef/callback/CefListValue_N", kCefClassName);
}

bool GetIndex(JNIEnv* env, jlong index, size_t* native_index) {
  return cef_value_util::ToSize(env, index, "index", native_index);
}

}  // namespace

JNIEXPORT jobject JNICALL
Java_org_cef_callback_CefListValue_1N_N_1Create(JNIEnv* env, jclass cls) {
  return NewJNIListValue(env, CefListValue::Create());
}

JNIEXPORT void JNICALL
Java_org_cef_callback_CefListValue_1N_N_1Dispose(JNIEnv* env,
                                                 jobject obj,
                                                 jlong self) {
  SetCefForJNIObject<CefListValue>(env, obj, nullptr, kCefClassName);
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefListValue_1N_N_1IsValid(JNIEnv* env,
                                                 jobject obj,
                                                 jlong self) {
  CefRefPtr<CefListValue> value = cef_value_util::GetSelf<CefListValue>(self);
  return value && value->IsValid() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefListValue_1N_N_1IsOwned(JNIEnv* env,
                                                 jobject obj,
                                                 jlong self) {
  CefRefPtr<CefListValue> value = cef_value_util::GetSelf<CefListValue>(self);
  if (!cef_value_util::RequireValid(env, value))
    return JNI_FALSE;
  return value->IsOwned() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefListValue_1N_N_1IsReadOnly(JNIEnv* env,
                                                    jobject obj,
                                                    jlong self) {
  CefRefPtr<CefListValue> value = cef_value_util::GetSelf<CefListValue>(self);
  if (!cef_value_util::RequireValid(env, value))
    return JNI_TRUE;
  return value->IsReadOnly() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefListValue_1N_N_1IsSame(JNIEnv* env,
                                                jobject obj,
                                                jlong self,
                                                jlong that) {
  CefRefPtr<CefListValue> value = cef_value_util::GetSelf<CefListValue>(self);
  CefRefPtr<CefListValue> other = cef_value_util::GetSelf<CefListValue>(that);
  if (!cef_value_util::RequireValid(env, value) ||
      !cef_value_util::RequireValid(env, other)) {
    return JNI_FALSE;
  }
  return value->IsSame(other) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefListValue_1N_N_1IsEqual(JNIEnv* env,
                                                 jobject obj,
                                                 jlong self,
                                                 jlong that) {
  CefRefPtr<CefListValue> value = cef_value_util::GetSelf<CefListValue>(self);
  CefRefPtr<CefListValue> other = cef_value_util::GetSelf<CefListValue>(that);
  if (!cef_value_util::RequireValid(env, value) ||
      !cef_value_util::RequireValid(env, other)) {
    return JNI_FALSE;
  }
  return value->IsEqual(other) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL
Java_org_cef_callback_CefListValue_1N_N_1Copy(JNIEnv* env,
                                              jobject obj,
                                              jlong self) {
  CefRefPtr<CefListValue> value = cef_value_util::GetSelf<CefListValue>(self);
  if (!cef_value_util::RequireValid(env, value))
    return nullptr;
  return NewJNIListValue(env, value->Copy());
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefListValue_1N_N_1SetSize(JNIEnv* env,
                                                 jobject obj,
                                                 jlong self,
                                                 jlong size) {
  CefRefPtr<CefListValue> value = cef_value_util::GetSelf<CefListValue>(self);
  if (!cef_value_util::RequireValid(env, value))
    return JNI_FALSE;
  size_t native_size = 0;
  if (!cef_value_util::ToSize(env, size, "size", &native_size))
    return JNI_FALSE;
  return value->SetSize(native_size) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_org_cef_callback_CefListValue_1N_N_1GetSize(JNIEnv* env,
                                                 jobject obj,
                                                 jlong self) {
  CefRefPtr<CefListValue> value = cef_value_util::GetSelf<CefListValue>(self);
  if (!cef_value_util::RequireValid(env, value))
    return 0;
  return cef_value_util::FromSize(env, value->GetSize());
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefListValue_1N_N_1Clear(JNIEnv* env,
                                               jobject obj,
                                               jlong self) {
  CefRefPtr<CefListValue> value = cef_value_util::GetSelf<CefListValue>(self);
  if (!cef_value_util::RequireValid(env, value))
    return JNI_FALSE;
  return value->Clear() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefListValue_1N_N_1Remove(JNIEnv* env,
                                                jobject obj,
                                                jlong self,
                                                jlong index) {
  CefRefPtr<CefListValue> value = cef_value_util::GetSelf<CefListValue>(self);
  size_t native_index = 0;
  if (!cef_value_util::RequireValid(env, value) ||
      !GetIndex(env, index, &native_index)) {
    return JNI_FALSE;
  }
  return value->Remove(native_index) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_org_cef_callback_CefListValue_1N_N_1GetType(JNIEnv* env,
                                                 jobject obj,
                                                 jlong self,
                                                 jlong index) {
  CefRefPtr<CefListValue> value = cef_value_util::GetSelf<CefListValue>(self);
  size_t native_index = 0;
  if (!cef_value_util::RequireValid(env, value) ||
      !GetIndex(env, index, &native_index)) {
    return VTYPE_INVALID;
  }
  return static_cast<jint>(value->GetType(native_index));
}

JNIEXPORT jobject JNICALL
Java_org_cef_callback_CefListValue_1N_N_1GetValue(JNIEnv* env,
                                                  jobject obj,
                                                  jlong self,
                                                  jlong index) {
  CefRefPtr<CefListValue> value = cef_value_util::GetSelf<CefListValue>(self);
  size_t native_index = 0;
  if (!cef_value_util::RequireValid(env, value) ||
      !GetIndex(env, index, &native_index)) {
    return nullptr;
  }
  return NewJNIValue(env, value->GetValue(native_index));
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefListValue_1N_N_1GetBool(JNIEnv* env,
                                                 jobject obj,
                                                 jlong self,
                                                 jlong index) {
  CefRefPtr<CefListValue> value = cef_value_util::GetSelf<CefListValue>(self);
  size_t native_index = 0;
  if (!cef_value_util::RequireValid(env, value) ||
      !GetIndex(env, index, &native_index)) {
    return JNI_FALSE;
  }
  return value->GetBool(native_index) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_org_cef_callback_CefListValue_1N_N_1GetInt(JNIEnv* env,
                                                jobject obj,
                                                jlong self,
                                                jlong index) {
  CefRefPtr<CefListValue> value = cef_value_util::GetSelf<CefListValue>(self);
  size_t native_index = 0;
  if (!cef_value_util::RequireValid(env, value) ||
      !GetIndex(env, index, &native_index)) {
    return 0;
  }
  return static_cast<jint>(value->GetInt(native_index));
}

JNIEXPORT jdouble JNICALL
Java_org_cef_callback_CefListValue_1N_N_1GetDouble(JNIEnv* env,
                                                   jobject obj,
                                                   jlong self,
                                                   jlong index) {
  CefRefPtr<CefListValue> value = cef_value_util::GetSelf<CefListValue>(self);
  size_t native_index = 0;
  if (!cef_value_util::RequireValid(env, value) ||
      !GetIndex(env, index, &native_index)) {
    return 0.0;
  }
  return static_cast<jdouble>(value->GetDouble(native_index));
}

JNIEXPORT jstring JNICALL
Java_org_cef_callback_CefListValue_1N_N_1GetString(JNIEnv* env,
                                                   jobject obj,
                                                   jlong self,
                                                   jlong index) {
  CefRefPtr<CefListValue> value = cef_value_util::GetSelf<CefListValue>(self);
  size_t native_index = 0;
  if (!cef_value_util::RequireValid(env, value) ||
      !GetIndex(env, index, &native_index)) {
    return nullptr;
  }
  return NewJNIString(env, value->GetString(native_index));
}

JNIEXPORT jobject JNICALL
Java_org_cef_callback_CefListValue_1N_N_1GetBinary(JNIEnv* env,
                                                   jobject obj,
                                                   jlong self,
                                                   jlong index) {
  CefRefPtr<CefListValue> value = cef_value_util::GetSelf<CefListValue>(self);
  size_t native_index = 0;
  if (!cef_value_util::RequireValid(env, value) ||
      !GetIndex(env, index, &native_index)) {
    return nullptr;
  }
  return NewJNIBinaryValue(env, value->GetBinary(native_index));
}

JNIEXPORT jobject JNICALL
Java_org_cef_callback_CefListValue_1N_N_1GetDictionary(JNIEnv* env,
                                                       jobject obj,
                                                       jlong self,
                                                       jlong index) {
  CefRefPtr<CefListValue> value = cef_value_util::GetSelf<CefListValue>(self);
  size_t native_index = 0;
  if (!cef_value_util::RequireValid(env, value) ||
      !GetIndex(env, index, &native_index)) {
    return nullptr;
  }
  return NewJNIDictionaryValue(env, value->GetDictionary(native_index));
}

JNIEXPORT jobject JNICALL
Java_org_cef_callback_CefListValue_1N_N_1GetList(JNIEnv* env,
                                                 jobject obj,
                                                 jlong self,
                                                 jlong index) {
  CefRefPtr<CefListValue> value = cef_value_util::GetSelf<CefListValue>(self);
  size_t native_index = 0;
  if (!cef_value_util::RequireValid(env, value) ||
      !GetIndex(env, index, &native_index)) {
    return nullptr;
  }
  return NewJNIListValue(env, value->GetList(native_index));
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefListValue_1N_N_1SetValue(JNIEnv* env,
                                                  jobject obj,
                                                  jlong self,
                                                  jlong index,
                                                  jlong child) {
  CefRefPtr<CefListValue> value = cef_value_util::GetSelf<CefListValue>(self);
  CefRefPtr<CefValue> child_value = cef_value_util::GetSelf<CefValue>(child);
  size_t native_index = 0;
  if (!cef_value_util::RequireValid(env, value) ||
      !GetIndex(env, index, &native_index) ||
      !cef_value_util::RequireValid(env, child_value)) {
    return JNI_FALSE;
  }
  return value->SetValue(native_index, child_value) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefListValue_1N_N_1SetNull(JNIEnv* env,
                                                 jobject obj,
                                                 jlong self,
                                                 jlong index) {
  CefRefPtr<CefListValue> value = cef_value_util::GetSelf<CefListValue>(self);
  size_t native_index = 0;
  if (!cef_value_util::RequireValid(env, value) ||
      !GetIndex(env, index, &native_index)) {
    return JNI_FALSE;
  }
  return value->SetNull(native_index) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefListValue_1N_N_1SetBool(JNIEnv* env,
                                                 jobject obj,
                                                 jlong self,
                                                 jlong index,
                                                 jboolean child) {
  CefRefPtr<CefListValue> value = cef_value_util::GetSelf<CefListValue>(self);
  size_t native_index = 0;
  if (!cef_value_util::RequireValid(env, value) ||
      !GetIndex(env, index, &native_index)) {
    return JNI_FALSE;
  }
  return value->SetBool(native_index, child == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefListValue_1N_N_1SetInt(JNIEnv* env,
                                                jobject obj,
                                                jlong self,
                                                jlong index,
                                                jint child) {
  CefRefPtr<CefListValue> value = cef_value_util::GetSelf<CefListValue>(self);
  size_t native_index = 0;
  if (!cef_value_util::RequireValid(env, value) ||
      !GetIndex(env, index, &native_index)) {
    return JNI_FALSE;
  }
  return value->SetInt(native_index, static_cast<int>(child)) ? JNI_TRUE
                                                              : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefListValue_1N_N_1SetDouble(JNIEnv* env,
                                                   jobject obj,
                                                   jlong self,
                                                   jlong index,
                                                   jdouble child) {
  CefRefPtr<CefListValue> value = cef_value_util::GetSelf<CefListValue>(self);
  size_t native_index = 0;
  if (!cef_value_util::RequireValid(env, value) ||
      !GetIndex(env, index, &native_index)) {
    return JNI_FALSE;
  }
  return value->SetDouble(native_index, static_cast<double>(child)) ? JNI_TRUE
                                                                    : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefListValue_1N_N_1SetString(JNIEnv* env,
                                                   jobject obj,
                                                   jlong self,
                                                   jlong index,
                                                   jstring child) {
  CefRefPtr<CefListValue> value = cef_value_util::GetSelf<CefListValue>(self);
  size_t native_index = 0;
  if (!cef_value_util::RequireValid(env, value) ||
      !GetIndex(env, index, &native_index) ||
      !cef_value_util::RequireString(env, child, "value must not be null")) {
    return JNI_FALSE;
  }
  return value->SetString(native_index, GetJNIString(env, child)) ? JNI_TRUE
                                                                  : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefListValue_1N_N_1SetBinary(JNIEnv* env,
                                                   jobject obj,
                                                   jlong self,
                                                   jlong index,
                                                   jlong child) {
  CefRefPtr<CefListValue> value = cef_value_util::GetSelf<CefListValue>(self);
  CefRefPtr<CefBinaryValue> child_value =
      cef_value_util::GetSelf<CefBinaryValue>(child);
  size_t native_index = 0;
  if (!cef_value_util::RequireValid(env, value) ||
      !GetIndex(env, index, &native_index) ||
      !cef_value_util::RequireValid(env, child_value)) {
    return JNI_FALSE;
  }
  return value->SetBinary(native_index, child_value) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefListValue_1N_N_1SetDictionary(JNIEnv* env,
                                                       jobject obj,
                                                       jlong self,
                                                       jlong index,
                                                       jlong child) {
  CefRefPtr<CefListValue> value = cef_value_util::GetSelf<CefListValue>(self);
  CefRefPtr<CefDictionaryValue> child_value =
      cef_value_util::GetSelf<CefDictionaryValue>(child);
  size_t native_index = 0;
  if (!cef_value_util::RequireValid(env, value) ||
      !GetIndex(env, index, &native_index) ||
      !cef_value_util::RequireValid(env, child_value)) {
    return JNI_FALSE;
  }
  return value->SetDictionary(native_index, child_value) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_callback_CefListValue_1N_N_1SetList(JNIEnv* env,
                                                 jobject obj,
                                                 jlong self,
                                                 jlong index,
                                                 jlong child) {
  CefRefPtr<CefListValue> value = cef_value_util::GetSelf<CefListValue>(self);
  CefRefPtr<CefListValue> child_value =
      cef_value_util::GetSelf<CefListValue>(child);
  size_t native_index = 0;
  if (!cef_value_util::RequireValid(env, value) ||
      !GetIndex(env, index, &native_index) ||
      !cef_value_util::RequireValid(env, child_value)) {
    return JNI_FALSE;
  }
  return value->SetList(native_index, child_value) ? JNI_TRUE : JNI_FALSE;
}
