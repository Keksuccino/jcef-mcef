// Copyright (c) 2013 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "jni_util.h"

#include <jawt.h>
#include <algorithm>
#include <cstdint>
#include <limits>
#include <string>
#include <string_view>

#include "jni_scoped_helpers.h"

#include "include/cef_base.h"

namespace {

JavaVM* g_jvm = nullptr;

jobject g_javaClassLoader = nullptr;

// NewStringUTF consumes JNI modified UTF-8, not the standard UTF-8 emitted by
// CEF and Chromium. Keep this converter independent of CEF runtime functions:
// it is also used while the macOS CEF framework may not yet be loaded.
std::u16string UTF8ToUTF16(std::string_view input) {
  std::u16string output;
  output.reserve(input.size());

  size_t offset = 0;
  while (offset < input.size()) {
    const uint8_t first = static_cast<uint8_t>(input[offset]);
    uint32_t code_point = 0;
    size_t sequence_length = 0;

    if (first <= 0x7F) {
      code_point = first;
      sequence_length = 1;
    } else if (first >= 0xC2 && first <= 0xDF && offset + 1 < input.size()) {
      const uint8_t second = static_cast<uint8_t>(input[offset + 1]);
      if ((second & 0xC0) == 0x80) {
        code_point = ((first & 0x1F) << 6) | (second & 0x3F);
        sequence_length = 2;
      }
    } else if (first >= 0xE0 && first <= 0xEF && offset + 2 < input.size()) {
      const uint8_t second = static_cast<uint8_t>(input[offset + 1]);
      const uint8_t third = static_cast<uint8_t>(input[offset + 2]);
      const bool valid_second = (second & 0xC0) == 0x80 &&
                                (first != 0xE0 || second >= 0xA0) &&
                                (first != 0xED || second <= 0x9F);
      if (valid_second && (third & 0xC0) == 0x80) {
        code_point =
            ((first & 0x0F) << 12) | ((second & 0x3F) << 6) | (third & 0x3F);
        sequence_length = 3;
      }
    } else if (first >= 0xF0 && first <= 0xF4 && offset + 3 < input.size()) {
      const uint8_t second = static_cast<uint8_t>(input[offset + 1]);
      const uint8_t third = static_cast<uint8_t>(input[offset + 2]);
      const uint8_t fourth = static_cast<uint8_t>(input[offset + 3]);
      const bool valid_second = (second & 0xC0) == 0x80 &&
                                (first != 0xF0 || second >= 0x90) &&
                                (first != 0xF4 || second <= 0x8F);
      if (valid_second && (third & 0xC0) == 0x80 && (fourth & 0xC0) == 0x80) {
        code_point = ((first & 0x07) << 18) | ((second & 0x3F) << 12) |
                     ((third & 0x3F) << 6) | (fourth & 0x3F);
        sequence_length = 4;
      }
    }

    if (sequence_length == 0) {
      output.push_back(u'\uFFFD');
      ++offset;
      continue;
    }

    if (code_point <= 0xFFFF) {
      output.push_back(static_cast<char16_t>(code_point));
    } else {
      code_point -= 0x10000;
      output.push_back(static_cast<char16_t>(0xD800 + (code_point >> 10)));
      output.push_back(static_cast<char16_t>(0xDC00 + (code_point & 0x3FF)));
    }
    offset += sequence_length;
  }
  return output;
}

std::string UTF16ToUTF8(std::u16string_view input) {
  std::string output;
  output.reserve(input.size());

  size_t offset = 0;
  while (offset < input.size()) {
    uint32_t code_point = input[offset++];
    if (code_point >= 0xD800 && code_point <= 0xDBFF) {
      if (offset < input.size()) {
        const uint32_t low_surrogate = input[offset];
        if (low_surrogate >= 0xDC00 && low_surrogate <= 0xDFFF) {
          code_point = 0x10000 + ((code_point - 0xD800) << 10) +
                       (low_surrogate - 0xDC00);
          ++offset;
        } else {
          code_point = 0xFFFD;
        }
      } else {
        code_point = 0xFFFD;
      }
    } else if (code_point >= 0xDC00 && code_point <= 0xDFFF) {
      code_point = 0xFFFD;
    }

    if (code_point <= 0x7F) {
      output.push_back(static_cast<char>(code_point));
    } else if (code_point <= 0x7FF) {
      output.push_back(static_cast<char>(0xC0 | (code_point >> 6)));
      output.push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
    } else if (code_point <= 0xFFFF) {
      output.push_back(static_cast<char>(0xE0 | (code_point >> 12)));
      output.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3F)));
      output.push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
    } else {
      output.push_back(static_cast<char>(0xF0 | (code_point >> 18)));
      output.push_back(static_cast<char>(0x80 | ((code_point >> 12) & 0x3F)));
      output.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3F)));
      output.push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
    }
  }
  return output;
}

jstring NewJNIStringFromUTF16(JNIEnv* env, std::u16string_view value) {
  if (value.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
    ScopedJNIClass exception_class(env, "java/lang/OutOfMemoryError");
    if (exception_class)
      env->ThrowNew(exception_class, "Native string exceeds the maximum Java String length");
    return nullptr;
  }

  static_assert(sizeof(jchar) == sizeof(std::u16string::value_type),
                "JNI and C++ UTF-16 code units must have the same width");
  const char16_t* chars = value.empty() ? u"" : value.data();
  return env->NewString(reinterpret_cast<const jchar*>(chars), static_cast<jsize>(value.size()));
}

}  // namespace

void SetJVM(JavaVM* jvm) {
  ASSERT(!g_jvm);
  g_jvm = jvm;
}

JavaVM* GetJVM() {
  return g_jvm;
}

bool SetJavaClassLoader(JNIEnv* env, jobject javaClassLoader) {
  ASSERT(!g_javaClassLoader);
  g_javaClassLoader = env->NewGlobalRef(javaClassLoader);
  if (!g_javaClassLoader) {
    if (env->ExceptionCheck())
      env->ExceptionClear();
    return false;
  }
  return true;
}

jobject GetJavaClassLoader() {
  return g_javaClassLoader;
}

void ClearJNIReferences(JNIEnv* env) {
  ClearJNIClassCache(env);
  if (g_javaClassLoader) {
    env->DeleteGlobalRef(g_javaClassLoader);
    g_javaClassLoader = nullptr;
  }
  g_jvm = nullptr;
}

jobject NewJNIObject(JNIEnv* env, jclass cls) {
  jmethodID initID = env->GetMethodID(cls, "<init>", "()V");
  if (initID == 0) {
    env->ExceptionClear();
    return nullptr;
  }

  jobject obj = env->NewObject(cls, initID);
  if (obj == nullptr) {
    env->ExceptionClear();
    return nullptr;
  }

  return obj;
}

jobject NewJNIObject(JNIEnv* env, const char* class_name) {
  ScopedJNIClass cls(env, class_name);
  if (!cls)
    return nullptr;

  return NewJNIObject(env, cls);
}

jobject NewJNIObject(JNIEnv* env,
                     const char* class_name,
                     const char* sig,
                     ...) {
  ScopedJNIClass cls(env, class_name);
  if (!cls)
    return nullptr;

  jmethodID initID = env->GetMethodID(cls, "<init>", sig);
  if (initID == 0) {
    env->ExceptionClear();
    return nullptr;
  }

  va_list ap;
  va_start(ap, sig);

  jobject obj = env->NewObjectV(cls, initID, ap);
  if (obj == nullptr) {
    env->ExceptionClear();
    return nullptr;
  }

  return obj;
}

bool GetJNIBoolRef(JNIEnv* env, jobject jboolRef) {
  jboolean boolRefRes = JNI_FALSE;
  JNI_CALL_METHOD(env, jboolRef, "get", "()Z", Boolean, boolRefRes);
  return (boolRefRes != JNI_FALSE);
}

int GetJNIIntRef(JNIEnv* env, jobject jintRef) {
  jint intRefRes = -1;
  JNI_CALL_METHOD(env, jintRef, "get", "()I", Int, intRefRes);
  return intRefRes;
}

int64_t GetJNILongRef(JNIEnv* env, jobject jlongRef) {
  jlong longRefRes = -1;
  JNI_CALL_METHOD(env, jlongRef, "get", "()J", Long, longRefRes);
  return longRefRes;
}

CefString GetJNIStringRef(JNIEnv* env, jobject jstringRef) {
  ScopedJNIStringResult str(env);
  JNI_CALL_METHOD(env, jstringRef, "get", "()Ljava/lang/String;", Object, str);
  return str.GetCefString();
}

void SetJNIBoolRef(JNIEnv* env, jobject jboolRef, bool boolValue) {
  JNI_CALL_VOID_METHOD(env, jboolRef, "set", "(Z)V",
                       (boolValue ? JNI_TRUE : JNI_FALSE));
}

void SetJNIIntRef(JNIEnv* env, jobject jintRef, int intValue) {
  JNI_CALL_VOID_METHOD(env, jintRef, "set", "(I)V", intValue);
}

void SetJNILongRef(JNIEnv* env, jobject jlongRef, int64_t longValue) {
  JNI_CALL_VOID_METHOD(env, jlongRef, "set", "(J)V", longValue);
}

bool SetJNIStringRef(JNIEnv* env, jobject jstringRef, const CefString& stringValue) {
  ScopedJNIObjectLocal string(env, NewJNIString(env, stringValue));
  if (!string) {
    if (env->ExceptionCheck()) {
      env->ExceptionDescribe();
      env->ExceptionClear();
    }
    return false;
  }
  JNI_CALL_VOID_METHOD(env, jstringRef, "set", "(Ljava/lang/String;)V", string.get());
  return true;
}

jstring NewJNIString(JNIEnv* env, const std::string& str) {
  return NewJNIStringFromUTF16(env, UTF8ToUTF16(str));
}

jstring NewJNIString(JNIEnv* env, const CefString& str) {
  const char16_t* chars = str.empty() ? u"" : str.c_str();
  return NewJNIStringFromUTF16(env, std::u16string_view(chars, str.length()));
}

jstring NewJNIString(JNIEnv* env, const char* str) {
  return str ? NewJNIString(env, std::string(str)) : nullptr;
}

std::string GetJNIStringUTF8(JNIEnv* env, jstring jstr) {
  if (!jstr)
    return std::string();

  const jsize length = env->GetStringLength(jstr);
  if (length == 0)
    return std::string();

  const jchar* utf16 = env->GetStringChars(jstr, nullptr);
  if (!utf16)
    return std::string();

  static_assert(sizeof(jchar) == sizeof(std::u16string::value_type),
                "JNI and C++ UTF-16 code units must have the same width");
  const std::string utf8 = UTF16ToUTF8(std::u16string_view(reinterpret_cast<const std::u16string::value_type*>(utf16), static_cast<size_t>(length)));
  env->ReleaseStringChars(jstr, utf16);
  return utf8;
}

CefString GetJNIString(JNIEnv* env, jstring jstr) {
  if (!jstr)
    return CefString();

  const jsize length = env->GetStringLength(jstr);
  if (length == 0)
    return CefString();

  const jchar* utf16 = env->GetStringChars(jstr, nullptr);
  if (!utf16)
    return CefString();

  static_assert(sizeof(jchar) == sizeof(std::u16string::value_type),
                "JNI and C++ UTF-16 code units must have the same width");
  CefString cef_str(reinterpret_cast<const std::u16string::value_type*>(utf16), static_cast<size_t>(length));
  env->ReleaseStringChars(jstr, utf16);
  return cef_str;
}

jobjectArray NewJNIStringArray(JNIEnv* env,
                               const std::vector<CefString>& vals) {
  if (vals.empty())
    return nullptr;

  ScopedJNIClass cls(env, "java/lang/String");
  if (!cls)
    return nullptr;

  const jsize size = static_cast<jsize>(vals.size());
  jobjectArray arr = env->NewObjectArray(size, cls, nullptr);

  for (jsize i = 0; i < size; i++) {
    ScopedJNIString str(env, vals[i]);
    env->SetObjectArrayElement(arr, i, str);
  }

  return arr;
}

void GetJNIStringArray(JNIEnv* env,
                       jobjectArray jarray,
                       std::vector<CefString>& vals) {
  jsize argc = env->GetArrayLength(jarray);
  for (jsize i = 0; i < argc; ++i) {
    ScopedJNIStringResult str(env,
                              (jstring)env->GetObjectArrayElement(jarray, i));
    vals.push_back(str.GetCefString());
  }
}

jobject NewJNIStringVector(JNIEnv* env, const std::vector<CefString>& vals) {
  ScopedJNIObjectLocal jvector(env, "java/util/Vector");
  if (!jvector)
    return nullptr;

  std::vector<CefString>::const_iterator iter;
  for (iter = vals.begin(); iter != vals.end(); ++iter) {
    AddJNIStringToVector(env, jvector, *iter);
  }
  return jvector.Release();
}

void AddJNIStringToVector(JNIEnv* env, jobject jvector, const CefString& str) {
  ScopedJNIString argument(env, str);
  JNI_CALL_VOID_METHOD(env, jvector, "addElement", "(Ljava/lang/Object;)V",
                       argument.get());
}

void GetJNIStringVector(JNIEnv* env,
                        jobject jvector,
                        std::vector<CefString>& vals) {
  if (!jvector)
    return;

  jint jsize = 0;
  JNI_CALL_METHOD(env, jvector, "size", "()I", Int, jsize);

  for (jint index = 0; index < jsize; index++) {
    ScopedJNIStringResult jstr(env);
    JNI_CALL_METHOD(env, jvector, "get", "(I)Ljava/lang/Object;", Object, jstr,
                    index);
    vals.push_back(jstr.GetCefString());
  }
}

jobject NewJNIStringMap(JNIEnv* env,
                        const std::map<CefString, CefString>& vals) {
  ScopedJNIObjectLocal jmap(env, "java/util/HashMap");
  if (!jmap)
    return nullptr;

  for (auto iter = vals.begin(); iter != vals.end(); ++iter) {
    ScopedJNIString jkey(env, iter->first);
    ScopedJNIString jvalue(env, iter->second);
    ScopedJNIObjectResult jresult(env);
    JNI_CALL_METHOD(env, jmap, "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    Object, jresult, jkey.get(), jvalue.get());
  }
  return jmap.Release();
}

void GetJNIStringMultiMap(JNIEnv* env,
                          jobject jheaderMap,
                          std::multimap<CefString, CefString>& vals) {
  if (!jheaderMap)
    return;

  // public abstract java.util.Set<java.util.Map$Entry<K, V>> entrySet();
  ScopedJNIObjectResult jentrySet(env);
  JNI_CALL_METHOD(env, jheaderMap, "entrySet", "()Ljava/util/Set;", Object,
                  jentrySet);
  if (!jentrySet)
    return;

  // public abstract java.lang.Object[] toArray();
  ScopedJNIObjectResult jentrySetValues(env);
  JNI_CALL_METHOD(env, jentrySet, "toArray", "()[Ljava/lang/Object;", Object,
                  jentrySetValues);
  if (!jentrySetValues)
    return;

  jint length = env->GetArrayLength((jobjectArray)jentrySetValues.get());
  for (jint i = 0; i < length; i++) {
    ScopedJNIObjectLocal jmapEntry(
        env,
        env->GetObjectArrayElement((jobjectArray)jentrySetValues.get(), i));
    if (!jmapEntry)
      return;
    ScopedJNIStringResult jkey(env);
    ScopedJNIStringResult jvalue(env);
    JNI_CALL_METHOD(env, jmapEntry, "getKey", "()Ljava/lang/Object;", Object,
                    jkey);
    JNI_CALL_METHOD(env, jmapEntry, "getValue", "()Ljava/lang/Object;", Object,
                    jvalue);
    vals.insert(std::make_pair(jkey.GetCefString(), jvalue.GetCefString()));
  }
}

void SetJNIStringMultiMap(JNIEnv* env,
                          jobject jheaderMap,
                          const std::multimap<CefString, CefString>& vals) {
  for (auto it = vals.begin(); it != vals.end(); ++it) {
    ScopedJNIString jkey(env, it->first);
    ScopedJNIString jvalue(env, it->second);
    ScopedJNIObjectResult jresult(env);
    JNI_CALL_METHOD(env, jheaderMap, "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    Object, jresult, jkey.get(), jvalue.get());
  }
}

void* GetJNIByteBufferData(JNIEnv* env, jobject jbyteBuffer) {
  if (!jbyteBuffer)
    return nullptr;

  void* data = nullptr;
  jlong capacity = env->GetDirectBufferCapacity(jbyteBuffer);
  if (capacity > 0) {
    data = env->GetDirectBufferAddress(jbyteBuffer);
  }
  return data;
}

size_t GetJNIByteBufferLength(JNIEnv* env, jobject jbyteBuffer) {
  if (!jbyteBuffer)
    return 0;

  return static_cast<size_t>(env->GetDirectBufferCapacity(jbyteBuffer));
}

CefMessageRouterConfig GetJNIMessageRouterConfig(JNIEnv* env, jobject jConfig) {
  CefMessageRouterConfig config;

  if (jConfig == nullptr)
    return config;
  ScopedJNIClass cls(env,
                     "org/cef/browser/CefMessageRouter$CefMessageRouterConfig");
  if (cls == nullptr)
    return config;

  GetJNIFieldString(env, cls, jConfig, "jsQueryFunction",
                    &config.js_query_function);
  GetJNIFieldString(env, cls, jConfig, "jsCancelFunction",
                    &config.js_cancel_function);
  return config;
}

namespace {

constexpr size_t kMaxCefValueContainerDepth = 64;

void ThrowIllegalArgumentException(JNIEnv* env, const std::string& message) {
  if (env->ExceptionCheck())
    return;
  jclass exception_class = env->FindClass("java/lang/IllegalArgumentException");
  if (!exception_class)
    return;
  env->ThrowNew(exception_class, message.c_str());
  env->DeleteLocalRef(exception_class);
}

bool IsJNIInstanceOf(JNIEnv* env, jobject obj, const char* class_name) {
  ScopedJNIClass cls(env, class_name);
  return cls && env->IsInstanceOf(obj, cls);
}

class ScopedContainerVisit {
 public:
  ScopedContainerVisit(JNIEnv* env,
                       jobject container,
                       std::vector<jobject>& active_containers)
      : active_containers_(active_containers) {
    if (active_containers_.size() >= kMaxCefValueContainerDepth) {
      ThrowIllegalArgumentException(
          env, "Preference value nesting exceeds the supported depth");
      return;
    }
    for (jobject active_container : active_containers_) {
      if (env->IsSameObject(container, active_container)) {
        ThrowIllegalArgumentException(
            env, "Preference value contains a cyclic Map/List reference");
        return;
      }
    }
    active_containers_.push_back(container);
    entered_ = true;
  }

  ~ScopedContainerVisit() {
    if (entered_)
      active_containers_.pop_back();
  }

  bool entered() const { return entered_; }

 private:
  std::vector<jobject>& active_containers_;
  bool entered_ = false;
};

CefRefPtr<CefValue> ConvertJNIObject(JNIEnv* env,
                                     jobject obj,
                                     std::vector<jobject>& active_containers);

CefRefPtr<CefValue> ConvertJNIByteBuffer(JNIEnv* env, jobject obj) {
  ScopedJNIClass byte_buffer_class(env, "java/nio/ByteBuffer");
  if (!byte_buffer_class)
    return nullptr;
  jmethodID remaining_method =
      env->GetMethodID(byte_buffer_class, "remaining", "()I");
  jmethodID duplicate_method = env->GetMethodID(byte_buffer_class, "duplicate",
                                                "()Ljava/nio/ByteBuffer;");
  jmethodID get_method =
      env->GetMethodID(byte_buffer_class, "get", "([B)Ljava/nio/ByteBuffer;");
  if (!remaining_method || !duplicate_method || !get_method)
    return nullptr;

  jint remaining = env->CallIntMethod(obj, remaining_method);
  if (env->ExceptionCheck())
    return nullptr;
  if (remaining < 0) {
    ThrowIllegalArgumentException(env,
                                  "ByteBuffer remaining() cannot be negative");
    return nullptr;
  }
  // CefBinaryValue has no representation for a zero-byte payload. Reject this
  // explicitly instead of silently changing the type to null or inserting a
  // sentinel byte that other CEF consumers would observe as real preference
  // data.
  if (remaining == 0) {
    ThrowIllegalArgumentException(
        env, "CEF does not support empty binary preference values");
    return nullptr;
  }

  ScopedJNIObjectLocal duplicate(env,
                                 env->CallObjectMethod(obj, duplicate_method));
  if (env->ExceptionCheck() || !duplicate)
    return nullptr;
  ScopedJNIObjectLocal bytes(env, env->NewByteArray(remaining));
  if (!bytes)
    return nullptr;
  ScopedJNIObjectLocal get_result(
      env, env->CallObjectMethod(duplicate.get(), get_method, bytes.get()));
  if (env->ExceptionCheck() || !get_result)
    return nullptr;

  std::vector<jbyte> data(static_cast<size_t>(remaining));
  if (remaining > 0) {
    env->GetByteArrayRegion(static_cast<jbyteArray>(bytes.get()), 0, remaining,
                            data.data());
    if (env->ExceptionCheck())
      return nullptr;
  }

  CefRefPtr<CefBinaryValue> binary =
      CefBinaryValue::Create(data.data(), data.size());
  if (!binary) {
    ThrowIllegalArgumentException(env,
                                  "Failed to create binary preference value");
    return nullptr;
  }
  CefRefPtr<CefValue> value = CefValue::Create();
  if (!value->SetBinary(binary)) {
    ThrowIllegalArgumentException(env, "Failed to set binary preference value");
    return nullptr;
  }
  return value;
}

CefRefPtr<CefValue> ConvertJNIMap(JNIEnv* env,
                                  jobject obj,
                                  std::vector<jobject>& active_containers) {
  ScopedContainerVisit visit(env, obj, active_containers);
  if (!visit.entered())
    return nullptr;

  ScopedJNIClass map_class(env, "java/util/Map");
  ScopedJNIClass set_class(env, "java/util/Set");
  ScopedJNIClass iterator_class(env, "java/util/Iterator");
  ScopedJNIClass entry_class(env, "java/util/Map$Entry");
  ScopedJNIClass string_class(env, "java/lang/String");
  if (!map_class || !set_class || !iterator_class || !entry_class ||
      !string_class) {
    return nullptr;
  }
  jmethodID entry_set_method =
      env->GetMethodID(map_class, "entrySet", "()Ljava/util/Set;");
  jmethodID iterator_method =
      env->GetMethodID(set_class, "iterator", "()Ljava/util/Iterator;");
  jmethodID has_next_method =
      env->GetMethodID(iterator_class, "hasNext", "()Z");
  jmethodID next_method =
      env->GetMethodID(iterator_class, "next", "()Ljava/lang/Object;");
  jmethodID get_key_method =
      env->GetMethodID(entry_class, "getKey", "()Ljava/lang/Object;");
  jmethodID get_value_method =
      env->GetMethodID(entry_class, "getValue", "()Ljava/lang/Object;");
  if (!entry_set_method || !iterator_method || !has_next_method ||
      !next_method || !get_key_method || !get_value_method) {
    return nullptr;
  }

  ScopedJNIObjectLocal entry_set(env,
                                 env->CallObjectMethod(obj, entry_set_method));
  if (env->ExceptionCheck() || !entry_set)
    return nullptr;
  ScopedJNIObjectLocal iterator(
      env, env->CallObjectMethod(entry_set.get(), iterator_method));
  if (env->ExceptionCheck() || !iterator)
    return nullptr;

  CefRefPtr<CefDictionaryValue> dictionary = CefDictionaryValue::Create();
  while (env->CallBooleanMethod(iterator.get(), has_next_method) == JNI_TRUE) {
    if (env->ExceptionCheck())
      return nullptr;
    ScopedJNIObjectLocal entry(
        env, env->CallObjectMethod(iterator.get(), next_method));
    if (env->ExceptionCheck() || !entry)
      return nullptr;
    ScopedJNIObjectLocal entry_key(
        env, env->CallObjectMethod(entry.get(), get_key_method));
    if (env->ExceptionCheck())
      return nullptr;
    if (!entry_key || !env->IsInstanceOf(entry_key.get(), string_class)) {
      ThrowIllegalArgumentException(
          env, "Preference Map keys must be non-null String values");
      return nullptr;
    }

    CefString key = GetJNIString(env, static_cast<jstring>(entry_key.get()));
    ScopedJNIObjectLocal entry_value(
        env, env->CallObjectMethod(entry.get(), get_value_method));
    if (env->ExceptionCheck())
      return nullptr;
    CefRefPtr<CefValue> cef_value =
        ConvertJNIObject(env, entry_value.get(), active_containers);
    if (!cef_value) {
      if (!env->ExceptionCheck()) {
        ThrowIllegalArgumentException(
            env, "Failed to convert preference Map value for key '" +
                     key.ToString() + "'");
      }
      return nullptr;
    }
    if (!dictionary->SetValue(key, cef_value)) {
      ThrowIllegalArgumentException(
          env, "Failed to set preference Map value for key '" + key.ToString() +
                   "'");
      return nullptr;
    }
  }
  if (env->ExceptionCheck())
    return nullptr;

  CefRefPtr<CefValue> value = CefValue::Create();
  if (!value->SetDictionary(dictionary)) {
    ThrowIllegalArgumentException(env,
                                  "Failed to set dictionary preference value");
    return nullptr;
  }
  return value;
}

CefRefPtr<CefValue> ConvertJNIList(JNIEnv* env,
                                   jobject obj,
                                   std::vector<jobject>& active_containers) {
  ScopedContainerVisit visit(env, obj, active_containers);
  if (!visit.entered())
    return nullptr;

  ScopedJNIClass list_class(env, "java/util/List");
  if (!list_class)
    return nullptr;
  jmethodID size_method = env->GetMethodID(list_class, "size", "()I");
  jmethodID get_method =
      env->GetMethodID(list_class, "get", "(I)Ljava/lang/Object;");
  if (!size_method || !get_method)
    return nullptr;

  jint size = env->CallIntMethod(obj, size_method);
  if (env->ExceptionCheck())
    return nullptr;
  if (size < 0) {
    ThrowIllegalArgumentException(env,
                                  "Preference List size cannot be negative");
    return nullptr;
  }

  CefRefPtr<CefListValue> list = CefListValue::Create();
  for (jint index = 0; index < size; ++index) {
    ScopedJNIObjectLocal element(env,
                                 env->CallObjectMethod(obj, get_method, index));
    if (env->ExceptionCheck())
      return nullptr;
    CefRefPtr<CefValue> cef_value =
        ConvertJNIObject(env, element.get(), active_containers);
    if (!cef_value) {
      if (!env->ExceptionCheck()) {
        ThrowIllegalArgumentException(
            env, "Failed to convert preference List value at index " +
                     std::to_string(index));
      }
      return nullptr;
    }
    if (!list->SetValue(static_cast<size_t>(index), cef_value)) {
      ThrowIllegalArgumentException(
          env, "Failed to set preference List value at index " +
                   std::to_string(index));
      return nullptr;
    }
  }

  CefRefPtr<CefValue> value = CefValue::Create();
  if (!value->SetList(list)) {
    ThrowIllegalArgumentException(env, "Failed to set list preference value");
    return nullptr;
  }
  return value;
}

CefRefPtr<CefValue> ConvertJNIObject(JNIEnv* env,
                                     jobject obj,
                                     std::vector<jobject>& active_containers) {
  if (!obj) {
    CefRefPtr<CefValue> value = CefValue::Create();
    if (!value->SetNull()) {
      ThrowIllegalArgumentException(env, "Failed to set null preference value");
      return nullptr;
    }
    return value;
  }

  if (IsJNIInstanceOf(env, obj, "java/lang/Boolean"))
    return GetCefValueFromJNIBoolean(env, obj);
  if (IsJNIInstanceOf(env, obj, "java/lang/Integer"))
    return GetCefValueFromJNIInteger(env, obj);
  if (IsJNIInstanceOf(env, obj, "java/lang/Double"))
    return GetCefValueFromJNIDouble(env, obj);
  if (IsJNIInstanceOf(env, obj, "java/lang/String"))
    return GetCefValueFromJNIString(env, obj);
  if (IsJNIInstanceOf(env, obj, "java/nio/ByteBuffer"))
    return ConvertJNIByteBuffer(env, obj);
  if (IsJNIInstanceOf(env, obj, "java/util/Map"))
    return ConvertJNIMap(env, obj, active_containers);
  if (IsJNIInstanceOf(env, obj, "java/util/List"))
    return ConvertJNIList(env, obj, active_containers);

  ThrowIllegalArgumentException(
      env,
      "Unsupported preference value type; expected null, Boolean, Integer, "
      "Double, String, ByteBuffer, Map, or List");
  return nullptr;
}

}  // namespace

CefRefPtr<CefValue> GetCefValueFromJNIObject(JNIEnv* env, jobject obj) {
  std::vector<jobject> active_containers;
  return ConvertJNIObject(env, obj, active_containers);
}

CefRefPtr<CefValue> GetCefValueFromJNIBoolean(JNIEnv* env, const jobject& obj) {
  CefRefPtr<CefValue> value = CefValue::Create();
  if (!value->SetBool(GetJNIBoolean(env, obj))) {
    ThrowIllegalArgumentException(env,
                                  "Failed to set Boolean preference value");
    return nullptr;
  }
  return value;
}

CefRefPtr<CefValue> GetCefValueFromJNIInteger(JNIEnv* env, const jobject& obj) {
  CefRefPtr<CefValue> value = CefValue::Create();
  if (!value->SetInt(GetJNIInteger(env, obj))) {
    ThrowIllegalArgumentException(env,
                                  "Failed to set Integer preference value");
    return nullptr;
  }
  return value;
}

CefRefPtr<CefValue> GetCefValueFromJNIDouble(JNIEnv* env, const jobject& obj) {
  CefRefPtr<CefValue> value = CefValue::Create();
  if (!value->SetDouble(GetJNIDouble(env, obj))) {
    ThrowIllegalArgumentException(env, "Failed to set Double preference value");
    return nullptr;
  }
  return value;
}

CefRefPtr<CefValue> GetCefValueFromJNIString(JNIEnv* env, const jobject& obj) {
  CefRefPtr<CefValue> value = CefValue::Create();
  if (!value->SetString(GetJNIString(env, static_cast<jstring>(obj)))) {
    ThrowIllegalArgumentException(env, "Failed to set String preference value");
    return nullptr;
  }
  return value;
}

CefRefPtr<CefValue> GetCefValueFromJNIByteBuffer(JNIEnv* env,
                                                 const jobject& obj) {
  return ConvertJNIByteBuffer(env, obj);
}

CefRefPtr<CefValue> GetCefValueFromJNIMap(JNIEnv* env, const jobject& obj) {
  std::vector<jobject> active_containers;
  return ConvertJNIMap(env, obj, active_containers);
}

CefRefPtr<CefValue> GetCefValueFromJNIList(JNIEnv* env, const jobject& obj) {
  std::vector<jobject> active_containers;
  return ConvertJNIList(env, obj, active_containers);
}

jobject NewJNIErrorCode(JNIEnv* env, cef_errorcode_t errorCode) {
  ScopedJNIClass cls(env, "org/cef/handler/CefLoadHandler$ErrorCode");
  if (!cls)
    return nullptr;

  jmethodID find_by_code = env->GetStaticMethodID(
      cls, "findByCode", "(I)Lorg/cef/handler/CefLoadHandler$ErrorCode;");
  if (!find_by_code)
    return nullptr;

  static_assert(sizeof(cef_errorcode_t) == sizeof(jint));
  return env->CallStaticObjectMethod(cls, find_by_code,
                                     static_cast<jint>(errorCode));
}

jobject NewJNIBoolean(JNIEnv* env, const bool value) {
  ScopedJNIClass cls(env, "java/lang/Boolean");
  if (!cls)
    return nullptr;

  jmethodID method =
      env->GetStaticMethodID(cls, "valueOf", "(Z)Ljava/lang/Boolean;");
  if (!method)
    return nullptr;

  return env->CallStaticObjectMethod(cls, method, value ? JNI_TRUE : JNI_FALSE);
}

jboolean GetJNIBoolean(JNIEnv* env, jobject obj) {
  if (obj) {
    jboolean value = JNI_FALSE;
    JNI_CALL_METHOD(env, obj, "booleanValue", "()Z", Boolean, value);
    return value;
  }
  return JNI_FALSE;
}

jobject NewJNIInteger(JNIEnv* env, const int value) {
  ScopedJNIClass cls(env, "java/lang/Integer");
  if (!cls)
    return nullptr;

  jmethodID method =
      env->GetStaticMethodID(cls, "valueOf", "(I)Ljava/lang/Integer;");
  if (!method)
    return nullptr;

  return env->CallStaticObjectMethod(cls, method, value);
}

jint GetJNIInteger(JNIEnv* env, jobject obj) {
  if (obj) {
    jint value = 0;
    JNI_CALL_METHOD(env, obj, "intValue", "()I", Int, value);
    return value;
  }
  return 0;
}

jobject NewJNIDouble(JNIEnv* env, const double value) {
  ScopedJNIClass cls(env, "java/lang/Double");
  if (!cls)
    return nullptr;

  jmethodID method =
      env->GetStaticMethodID(cls, "valueOf", "(D)Ljava/lang/Double;");
  if (!method)
    return nullptr;

  return env->CallStaticObjectMethod(cls, method, value);
}

jdouble GetJNIDouble(JNIEnv* env, jobject obj) {
  if (obj) {
    jdouble value = 0;
    JNI_CALL_METHOD(env, obj, "doubleValue", "()D", Double, value);
    return value;
  }
  return 0;
}

jobject NewJNIByteBuffer(JNIEnv* env, const void* data, size_t size) {
  if (size > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
    ThrowIllegalArgumentException(
        env, "Binary preference value is too large for Java");
    return nullptr;
  }
  if (size > 0 && !data) {
    ThrowIllegalArgumentException(env, "Binary preference value has no data");
    return nullptr;
  }
  ScopedJNIClass cls(env, "java/nio/ByteBuffer");
  if (!cls)
    return nullptr;

  jmethodID method =
      env->GetStaticMethodID(cls, "wrap", "([B)Ljava/nio/ByteBuffer;");
  if (!method)
    return nullptr;

  ScopedJNIObjectLocal array(env, env->NewByteArray(static_cast<jsize>(size)));
  if (!array)
    return nullptr;

  if (size > 0) {
    env->SetByteArrayRegion(static_cast<jbyteArray>(array.get()), 0,
                            static_cast<jsize>(size),
                            reinterpret_cast<const jbyte*>(data));
    if (env->ExceptionCheck())
      return nullptr;
  }
  return env->CallStaticObjectMethod(cls, method, array.get());
}

jobject NewJNIHashMap(JNIEnv* env) {
  ScopedJNIClass cls(env, "java/util/HashMap");
  if (!cls)
    return nullptr;

  jmethodID method = env->GetMethodID(cls, "<init>", "()V");
  if (!method)
    return nullptr;

  return env->NewObject(cls, method);
}

jobject NewJNIArrayList(JNIEnv* env) {
  ScopedJNIClass cls(env, "java/util/ArrayList");
  if (!cls)
    return nullptr;

  jmethodID method = env->GetMethodID(cls, "<init>", "()V");
  if (!method)
    return nullptr;

  return env->NewObject(cls, method);
}

namespace {

jobject NewJNIObjectFromCefValueImpl(JNIEnv* env,
                                     const CefRefPtr<CefValue> value,
                                     size_t container_depth) {
  if (!value)
    return nullptr;
  switch (value->GetType()) {
    case VTYPE_NULL:
      return nullptr;
    case VTYPE_BOOL:
      return NewJNIBoolean(env, value->GetBool());
    case VTYPE_INT:
      return NewJNIInteger(env, value->GetInt());
    case VTYPE_DOUBLE:
      return NewJNIDouble(env, value->GetDouble());
    case VTYPE_STRING:
      return NewJNIString(env, value->GetString());
    case VTYPE_BINARY: {
      CefRefPtr<CefBinaryValue> binary = value->GetBinary();
      if (!binary)
        return nullptr;
      return NewJNIByteBuffer(env, binary->GetRawData(), binary->GetSize());
    }
    case VTYPE_DICTIONARY: {
      if (container_depth >= kMaxCefValueContainerDepth) {
        ThrowIllegalArgumentException(
            env, "CEF preference value nesting exceeds the supported depth");
        return nullptr;
      }
      CefRefPtr<CefDictionaryValue> dict = value->GetDictionary();
      if (!dict)
        return nullptr;
      ScopedJNIObjectLocal jmap(env, NewJNIHashMap(env));
      if (!jmap)
        return nullptr;
      ScopedJNIClass map_class(env, "java/util/HashMap");
      if (!map_class)
        return nullptr;
      jmethodID put_method = env->GetMethodID(
          map_class, "put",
          "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
      if (!put_method)
        return nullptr;
      CefDictionaryValue::KeyList keys;
      if (!dict->GetKeys(keys))
        return nullptr;
      for (const CefString& key : keys) {
        ScopedJNIString jkey(env, key);
        CefRefPtr<CefValue> child_value = dict->GetValue(key);
        if (!child_value)
          return nullptr;
        ScopedJNIObjectLocal jvalue(
            env, NewJNIObjectFromCefValueImpl(env, child_value,
                                              container_depth + 1));
        if (env->ExceptionCheck())
          return nullptr;
        ScopedJNIObjectLocal previous_value(
            env, env->CallObjectMethod(jmap.get(), put_method, jkey.get(),
                                       jvalue.get()));
        if (env->ExceptionCheck())
          return nullptr;
      }
      return jmap.Release();
    }
    case VTYPE_LIST: {
      if (container_depth >= kMaxCefValueContainerDepth) {
        ThrowIllegalArgumentException(
            env, "CEF preference value nesting exceeds the supported depth");
        return nullptr;
      }
      CefRefPtr<CefListValue> list = value->GetList();
      if (!list)
        return nullptr;
      ScopedJNIObjectLocal jlist(env, NewJNIArrayList(env));
      if (!jlist)
        return nullptr;
      ScopedJNIClass list_class(env, "java/util/ArrayList");
      if (!list_class)
        return nullptr;
      jmethodID add_method =
          env->GetMethodID(list_class, "add", "(Ljava/lang/Object;)Z");
      if (!add_method)
        return nullptr;
      const size_t size = list->GetSize();
      for (size_t i = 0; i < size; ++i) {
        CefRefPtr<CefValue> child_value = list->GetValue(i);
        if (!child_value)
          return nullptr;
        ScopedJNIObjectLocal jvalue(
            env, NewJNIObjectFromCefValueImpl(env, child_value,
                                              container_depth + 1));
        if (env->ExceptionCheck())
          return nullptr;
        if (env->CallBooleanMethod(jlist.get(), add_method, jvalue.get()) !=
            JNI_TRUE) {
          if (!env->ExceptionCheck()) {
            ThrowIllegalArgumentException(
                env, "Failed to add converted preference List value");
          }
          return nullptr;
        }
      }
      return jlist.Release();
    }
    default:
      NOTREACHED();
      return nullptr;
  }
}

}  // namespace

jobject NewJNIObjectFromCefValue(JNIEnv* env, const CefRefPtr<CefValue> value) {
  return NewJNIObjectFromCefValueImpl(env, value, 0);
}

cef_errorcode_t GetJNIErrorCode(JNIEnv* env, jobject jerrorCode) {
  cef_errorcode_t errorCode = ERR_NONE;

  if (jerrorCode) {
    jint jcode = 0;
    JNI_CALL_METHOD(env, jerrorCode, "getCode", "()I", Int, jcode);
    errorCode = static_cast<cef_errorcode_t>(jcode);
  }

  return errorCode;
}

bool GetJNIFieldObject(JNIEnv* env,
                       jclass cls,
                       jobject obj,
                       const char* field_name,
                       jobject* value,
                       const char* object_type) {
  jfieldID field = env->GetFieldID(cls, field_name, object_type);
  if (field) {
    *value = env->GetObjectField(obj, field);
    return *value != nullptr;
  }
  env->ExceptionClear();
  return false;
}

bool GetJNIFieldString(JNIEnv* env,
                       jclass cls,
                       jobject obj,
                       const char* field_name,
                       CefString* value) {
  jobject fieldobj = nullptr;
  if (GetJNIFieldObject(env, cls, obj, field_name, &fieldobj,
                        "Ljava/lang/String;")) {
    ScopedJNIStringResult str(env, (jstring)fieldobj);
    *value = str.GetCefString();
    return true;
  }
  return false;
}

bool GetJNIFieldDate(JNIEnv* env,
                     jclass cls,
                     jobject obj,
                     const char* field_name,
                     CefBaseTime* value) {
  jobject fieldobj = nullptr;
  if (GetJNIFieldObject(env, cls, obj, field_name, &fieldobj,
                        "Ljava/util/Date;")) {
    ScopedJNIObjectLocal jdate(env, fieldobj);
    long timestamp = 0;
    JNI_CALL_METHOD(env, jdate, "getTime", "()J", Long, timestamp);
    CefTime cef_time;
    cef_time.SetDoubleT((double)(timestamp / 1000));
    cef_time_to_basetime(&cef_time, value);
    return true;
  }
  return false;
}

bool GetJNIFieldBoolean(JNIEnv* env,
                        jclass cls,
                        jobject obj,
                        const char* field_name,
                        int* value) {
  jfieldID field = env->GetFieldID(cls, field_name, "Z");
  if (field) {
    *value = env->GetBooleanField(obj, field) != JNI_FALSE ? 1 : 0;
    return true;
  }
  env->ExceptionClear();
  return false;
}

bool GetJNIFieldDouble(JNIEnv* env,
                       jclass cls,
                       jobject obj,
                       const char* field_name,
                       double* value) {
  jfieldID field = env->GetFieldID(cls, field_name, "D");
  if (field) {
    *value = env->GetDoubleField(obj, field);
    return true;
  }
  env->ExceptionClear();
  return false;
}

bool GetJNIFieldInt(JNIEnv* env,
                    jclass cls,
                    jobject obj,
                    const char* field_name,
                    int* value) {
  jfieldID field = env->GetFieldID(cls, field_name, "I");
  if (field) {
    *value = env->GetIntField(obj, field);
    return true;
  }
  env->ExceptionClear();
  return false;
}

bool GetJNIFieldLong(JNIEnv* env,
                     jclass cls,
                     jobject obj,
                     const char* field_name,
                     jlong* value) {
  jfieldID field = env->GetFieldID(cls, field_name, "J");
  if (field) {
    *value = env->GetLongField(obj, field);
    return true;
  }
  env->ExceptionClear();
  return false;
}

bool SetJNIFieldInt(JNIEnv* env,
                    jclass cls,
                    jobject obj,
                    const char* field_name,
                    int value) {
  jfieldID field = env->GetFieldID(cls, field_name, "I");
  if (field) {
    env->SetIntField(obj, field, value);
    return true;
  }
  env->ExceptionClear();
  return false;
}

bool SetJNIFieldDouble(JNIEnv* env,
                       jclass cls,
                       jobject obj,
                       const char* field_name,
                       double value) {
  jfieldID field = env->GetFieldID(cls, field_name, "D");
  if (field) {
    env->SetDoubleField(obj, field, value);
    return true;
  }
  env->ExceptionClear();
  return false;
}

bool SetJNIFieldBoolean(JNIEnv* env,
                        jclass cls,
                        jobject obj,
                        const char* field_name,
                        int value) {
  jfieldID field = env->GetFieldID(cls, field_name, "Z");
  if (field) {
    env->SetBooleanField(obj, field, value == 0 ? 0 : 1);
    return true;
  }
  env->ExceptionClear();
  return false;
}

bool GetJNIFieldStaticInt(JNIEnv* env,
                          jclass cls,
                          const char* field_name,
                          int* value) {
  jfieldID field = env->GetStaticFieldID(cls, field_name, "I");
  if (field) {
    *value = env->GetStaticIntField(cls, field);
    return true;
  }
  env->ExceptionClear();
  return false;
}

bool CallStaticJNIMethodII_V(JNIEnv* env,
                             jclass cls,
                             const char* method_name,
                             int* value,
                             int arg) {
  jmethodID methodID = env->GetStaticMethodID(cls, method_name, "(I)I");
  if (methodID) {
    *value = env->CallStaticIntMethod(cls, methodID, arg);
    return true;
  }
  env->ExceptionClear();
  return false;
}

bool CallJNIMethodI_V(JNIEnv* env,
                      jclass cls,
                      jobject obj,
                      const char* method_name,
                      int* value) {
  jmethodID methodID = env->GetMethodID(cls, method_name, "()I");
  if (methodID) {
    *value = env->CallIntMethod(obj, methodID);
    return true;
  }
  env->ExceptionClear();
  return false;
}

bool CallJNIMethodC_V(JNIEnv* env,
                      jclass cls,
                      jobject obj,
                      const char* method_name,
                      char16_t* value) {
  jmethodID methodID = env->GetMethodID(cls, method_name, "()C");
  if (methodID) {
    *value = env->CallCharMethod(obj, methodID);
    return true;
  }
  env->ExceptionClear();
  return false;
}

bool CallJNIMethodD_V(JNIEnv* env,
                      jclass cls,
                      jobject obj,
                      const char* method_name,
                      double* value) {
  jmethodID methodID = env->GetMethodID(cls, method_name, "()D");
  if (methodID) {
    *value = env->CallDoubleMethod(obj, methodID);
    return true;
  }
  env->ExceptionClear();
  return false;
}

CefSize GetJNISize(JNIEnv* env, jobject obj) {
  CefSize size;

  ScopedJNIClass cls(env, "java/awt/Dimension");
  if (!cls)
    return size;

  int width, height;
  if (GetJNIFieldInt(env, cls, obj, "width", &width) &&
      GetJNIFieldInt(env, cls, obj, "height", &height)) {
    size.Set(width, height);
  }
  return size;
}

CefRect GetJNIRect(JNIEnv* env, jobject obj) {
  CefRect rect;

  ScopedJNIClass cls(env, "java/awt/Rectangle");
  if (!cls)
    return rect;

  int x, y, width, height;
  if (GetJNIFieldInt(env, cls, obj, "x", &x) &&
      GetJNIFieldInt(env, cls, obj, "y", &y) &&
      GetJNIFieldInt(env, cls, obj, "width", &width) &&
      GetJNIFieldInt(env, cls, obj, "height", &height)) {
    rect.Set(x, y, width, height);
    return rect;
  }

  return rect;
}

bool GetJNIPoint(JNIEnv* env, jobject obj, int* x, int* y) {
  ScopedJNIClass cls(env, "java/awt/Point");
  if (!cls)
    return false;

  if (GetJNIFieldInt(env, cls, obj, "x", x) &&
      GetJNIFieldInt(env, cls, obj, "y", y)) {
    return true;
  }

  return false;
}

CefRefPtr<CefBrowser> GetJNIBrowser(JNIEnv* env, jobject jbrowser) {
  return GetCefFromJNIObject<CefBrowser>(env, jbrowser, "CefBrowser");
}

jobject GetJNIEnumValue(JNIEnv* env,
                        const char* class_name,
                        const char* enum_valname) {
  ScopedJNIClass cls(env, class_name);
  if (!cls)
    return nullptr;

  std::string tmp;
  tmp.append("L").append(class_name).append(";");

  jfieldID fieldId = env->GetStaticFieldID(cls, enum_valname, tmp.c_str());
  if (!fieldId)
    return nullptr;

  return env->GetStaticObjectField(cls, fieldId);
}

bool IsJNIEnumValue(JNIEnv* env,
                    jobject jenum,
                    const char* class_name,
                    const char* enum_valname) {
  if (!jenum)
    return false;

  ScopedJNIObjectLocal compareTo(
      env, GetJNIEnumValue(env, class_name, enum_valname));
  if (compareTo) {
    jboolean isEqual = JNI_FALSE;
    JNI_CALL_METHOD(env, jenum, "equals", "(Ljava/lang/Object;)Z", Boolean,
                    isEqual, compareTo.get());
    return (isEqual != JNI_FALSE);
  }
  return false;
}
