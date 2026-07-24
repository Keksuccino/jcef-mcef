// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#ifndef JCEF_NATIVE_CEF_VALUE_UTIL_H_
#define JCEF_NATIVE_CEF_VALUE_UTIL_H_
#pragma once

#include <jni.h>

#include <limits>
#include <string>

#include "include/cef_values.h"
#include "jni_scoped_helpers.h"
#include "jni_util.h"

namespace cef_value_util {

static_assert(VTYPE_INVALID == 0 && VTYPE_NULL == 1 && VTYPE_BOOL == 2 &&
                  VTYPE_INT == 3 && VTYPE_DOUBLE == 4 && VTYPE_STRING == 5 &&
                  VTYPE_BINARY == 6 && VTYPE_DICTIONARY == 7 &&
                  VTYPE_LIST == 8 && VTYPE_NUM_VALUES == 9,
              "CefValueType Java mappings must be updated for this CEF API");

inline void ThrowJavaException(JNIEnv* env,
                               const char* class_name,
                               const char* message) {
  if (env->ExceptionCheck())
    return;
  ScopedJNIClass exception_class(env, class_name);
  if (exception_class)
    env->ThrowNew(exception_class, message);
}

template <class T>
CefRefPtr<T> GetSelf(jlong self) {
  return reinterpret_cast<T*>(self);
}

template <class T>
bool RequirePresent(JNIEnv* env, const CefRefPtr<T>& value) {
  if (value)
    return true;
  ThrowJavaException(env, "java/lang/IllegalStateException",
                     "CEF value has been disposed");
  return false;
}

template <class T>
bool RequireValid(JNIEnv* env, const CefRefPtr<T>& value) {
  if (!RequirePresent(env, value))
    return false;
  if (value->IsValid())
    return true;
  ThrowJavaException(env, "java/lang/IllegalStateException",
                     "CEF value data is no longer valid");
  return false;
}

template <class T>
jobject NewJNIValue(JNIEnv* env,
                    CefRefPtr<T> value,
                    const char* java_class_name,
                    const char* cef_class_name) {
  if (!value)
    return nullptr;
  ScopedJNIObject<T> jvalue(env, value, java_class_name, cef_class_name);
  return jvalue.Release();
}

inline bool ToSize(JNIEnv* env,
                   jlong value,
                   const char* argument_name,
                   size_t* result) {
  if (value < 0) {
    const std::string message =
        std::string(argument_name) + " must be non-negative";
    ThrowJavaException(env, "java/lang/IllegalArgumentException",
                       message.c_str());
    return false;
  }
  const uint64_t unsigned_value = static_cast<uint64_t>(value);
  if (unsigned_value > std::numeric_limits<size_t>::max()) {
    const std::string message =
        std::string(argument_name) + " exceeds native size_t";
    ThrowJavaException(env, "java/lang/IllegalArgumentException",
                       message.c_str());
    return false;
  }
  *result = static_cast<size_t>(unsigned_value);
  return true;
}

inline jlong FromSize(JNIEnv* env, size_t value) {
  if (value > static_cast<size_t>(std::numeric_limits<jlong>::max())) {
    ThrowJavaException(env, "java/lang/ArithmeticException",
                       "Native CEF size exceeds Java long range");
    return 0;
  }
  return static_cast<jlong>(value);
}

inline jobjectArray NewJNIStringArray(
    JNIEnv* env,
    const CefDictionaryValue::KeyList& values) {
  if (values.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
    ThrowJavaException(env, "java/lang/ArithmeticException",
                       "CEF key count exceeds Java array range");
    return nullptr;
  }
  ScopedJNIClass string_class(env, "java/lang/String");
  if (!string_class)
    return nullptr;
  jobjectArray result = env->NewObjectArray(static_cast<jsize>(values.size()),
                                            string_class, nullptr);
  if (!result)
    return nullptr;
  for (size_t i = 0; i < values.size(); ++i) {
    ScopedJNIString value(env, values[i]);
    env->SetObjectArrayElement(result, static_cast<jsize>(i), value.get());
    if (env->ExceptionCheck()) {
      env->DeleteLocalRef(result);
      return nullptr;
    }
  }
  return result;
}

inline bool RequireString(JNIEnv* env, jstring value, const char* name) {
  if (value)
    return true;
  ThrowJavaException(env, "java/lang/NullPointerException", name);
  return false;
}

}  // namespace cef_value_util

#endif  // JCEF_NATIVE_CEF_VALUE_UTIL_H_
