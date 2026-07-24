// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#ifndef JCEF_NATIVE_NETWORK_JNI_UTIL_H_
#define JCEF_NATIVE_NETWORK_JNI_UTIL_H_
#pragma once

#include <jni.h>

#include <cstdint>
#include <cstring>
#include <limits>

#include "include/internal/cef_types.h"
#include "jni_scoped_helpers.h"
#include "jni_util.h"

inline bool ThrowNetworkJNIException(JNIEnv* env,
                                     const char* class_name,
                                     const char* message) {
  if (env->ExceptionCheck())
    return false;
  jclass exception_class = env->FindClass(class_name);
  if (!exception_class)
    return false;
  env->ThrowNew(exception_class, message);
  env->DeleteLocalRef(exception_class);
  return false;
}

inline jint SaturateSizeToJInt(size_t value) {
  const size_t maximum = static_cast<size_t>(std::numeric_limits<jint>::max());
  return value > maximum ? std::numeric_limits<jint>::max()
                         : static_cast<jint>(value);
}

inline jlong SaturateUInt64ToJLong(uint64_t value) {
  const uint64_t maximum =
      static_cast<uint64_t>(std::numeric_limits<jlong>::max());
  return value > maximum ? std::numeric_limits<jlong>::max()
                         : static_cast<jlong>(value);
}

// cef_transition_type_t uses the high bit for TT_SERVER_REDIRECT_FLAG. Copy
// the representation so the raw bit pattern survives without an
// implementation-defined unsigned-to-signed narrowing conversion.
inline jint CefTransitionToJInt(cef_transition_type_t transition) {
  static_assert(sizeof(cef_transition_type_t) == sizeof(jint));
  uint32_t raw_value = static_cast<uint32_t>(transition);
  jint result;
  std::memcpy(&result, &raw_value, sizeof(result));
  return result;
}

inline bool GetValidatedByteArraySize(JNIEnv* env,
                                      jbyteArray bytes,
                                      jint requested_size,
                                      size_t* result) {
  if (!bytes) {
    return ThrowNetworkJNIException(env, "java/lang/NullPointerException",
                                    "bytes");
  }
  if (requested_size < 0) {
    return ThrowNetworkJNIException(env, "java/lang/IllegalArgumentException",
                                    "size must be between 0 and bytes.length");
  }
  const jsize array_size = env->GetArrayLength(bytes);
  if (requested_size > array_size) {
    return ThrowNetworkJNIException(env, "java/lang/IllegalArgumentException",
                                    "size must be between 0 and bytes.length");
  }
  *result = static_cast<size_t>(requested_size);
  return true;
}

template <class HeaderMap>
jobjectArray NewJNIHeaderPairs(JNIEnv* env, const HeaderMap& header_map) {
  const size_t maximum_headers =
      static_cast<size_t>(std::numeric_limits<jsize>::max()) / 2;
  if (header_map.size() > maximum_headers) {
    ThrowNetworkJNIException(env, "java/lang/IllegalStateException",
                             "Native header map is too large for Java");
    return nullptr;
  }

  ScopedJNIClass string_class(env, "java/lang/String");
  if (!string_class)
    return nullptr;
  const jsize array_size = static_cast<jsize>(header_map.size() * 2);
  jobjectArray result = env->NewObjectArray(array_size, string_class, nullptr);
  if (!result)
    return nullptr;

  jsize index = 0;
  for (const auto& header : header_map) {
    ScopedJNIObjectLocal name(env, NewJNIString(env, header.first));
    ScopedJNIObjectLocal value(env, NewJNIString(env, header.second));
    if (!name || !value) {
      env->DeleteLocalRef(result);
      return nullptr;
    }
    env->SetObjectArrayElement(result, index++, name.get());
    if (env->ExceptionCheck()) {
      env->DeleteLocalRef(result);
      return nullptr;
    }
    env->SetObjectArrayElement(result, index++, value.get());
    if (env->ExceptionCheck()) {
      env->DeleteLocalRef(result);
      return nullptr;
    }
  }
  return result;
}

template <class HeaderMap>
bool GetJNIHeaderPairs(JNIEnv* env,
                       jobjectArray header_pairs,
                       HeaderMap& header_map) {
  if (!header_pairs) {
    return ThrowNetworkJNIException(env, "java/lang/NullPointerException",
                                    "headerPairs");
  }
  const jsize array_size = env->GetArrayLength(header_pairs);
  if ((array_size & 1) != 0) {
    return ThrowNetworkJNIException(
        env, "java/lang/IllegalArgumentException",
        "Header array must contain ordered name/value pairs");
  }

  HeaderMap converted;
  for (jsize index = 0; index < array_size; index += 2) {
    ScopedJNIObjectLocal name(env,
                              env->GetObjectArrayElement(header_pairs, index));
    ScopedJNIObjectLocal value(
        env, env->GetObjectArrayElement(header_pairs, index + 1));
    if (env->ExceptionCheck())
      return false;
    if (!name || !value) {
      return ThrowNetworkJNIException(
          env, "java/lang/NullPointerException",
          "Header names and values must not be null");
    }
    // Sequential insertion into std::multimap preserves the relative order of
    // equivalent keys. Changing this to range construction or a Map conversion
    // would silently reorder or collapse duplicate header values.
    converted.emplace(GetJNIString(env, static_cast<jstring>(name.get())),
                      GetJNIString(env, static_cast<jstring>(value.get())));
    if (env->ExceptionCheck())
      return false;
  }
  header_map.swap(converted);
  return true;
}

#endif  // JCEF_NATIVE_NETWORK_JNI_UTIL_H_
