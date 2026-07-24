// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "CefPostDataElement_N.h"

#include "include/cef_request.h"

#include "jni_scoped_helpers.h"
#include "jni_util.h"
#include "network_jni_util.h"

namespace {

const char kCefClassName[] = "CefPostDataElement";

CefRefPtr<CefPostDataElement> GetSelf(jlong self) {
  return reinterpret_cast<CefPostDataElement*>(self);
}

}  // namespace

JNIEXPORT jobject JNICALL Java_org_cef_network_CefPostDataElement_1N_N_1Create(JNIEnv* env, jclass cls) {
  CefRefPtr<CefPostDataElement> dataElement = CefPostDataElement::Create();
  ScopedJNIPostDataElement jdataElement(env, dataElement);
  return jdataElement.Release();
}

JNIEXPORT void JNICALL Java_org_cef_network_CefPostDataElement_1N_N_1Dispose(JNIEnv* env, jobject obj, jlong self) {
  SetCefForJNIObject<CefPostDataElement>(env, obj, nullptr, kCefClassName);
}

JNIEXPORT jboolean JNICALL Java_org_cef_network_CefPostDataElement_1N_N_1IsReadOnly(JNIEnv* env, jobject obj, jlong self) {
  CefRefPtr<CefPostDataElement> dataElement = GetSelf(self);
  if (!dataElement)
    return JNI_FALSE;
  return dataElement->IsReadOnly() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_org_cef_network_CefPostDataElement_1N_N_1SetToEmpty(JNIEnv* env, jobject obj, jlong self) {
  CefRefPtr<CefPostDataElement> dataElement = GetSelf(self);
  if (!dataElement)
    return;
  dataElement->SetToEmpty();
}

JNIEXPORT void JNICALL Java_org_cef_network_CefPostDataElement_1N_N_1SetToFile(JNIEnv* env, jobject obj, jlong self, jstring jfilename) {
  CefRefPtr<CefPostDataElement> dataElement = GetSelf(self);
  if (!dataElement)
    return;
  dataElement->SetToFile(GetJNIString(env, jfilename));
}

JNIEXPORT void JNICALL Java_org_cef_network_CefPostDataElement_1N_N_1SetToBytes(JNIEnv* env, jobject obj, jlong self, jint jsize, jbyteArray jbytes) {
  CefRefPtr<CefPostDataElement> dataElement = GetSelf(self);
  if (!dataElement)
    return;

  size_t size;
  if (!GetValidatedByteArraySize(env, jbytes, jsize, &size))
    return;
  if (size == 0) {
    // CEF's C API rejects a null byte pointer before reaching SetToBytes, so a
    // zero-length update would otherwise leave the previous payload intact. The
    // representable zero-byte state is an empty element.
    dataElement->SetToEmpty();
    return;
  }
  jbyte* bytes = env->GetByteArrayElements(jbytes, nullptr);
  if (!bytes)
    return;
  dataElement->SetToBytes(size, bytes);
  env->ReleaseByteArrayElements(jbytes, bytes, JNI_ABORT);
}

JNIEXPORT jobject JNICALL Java_org_cef_network_CefPostDataElement_1N_N_1GetType(JNIEnv* env, jobject obj, jlong self) {
  CefRefPtr<CefPostDataElement> dataElement = GetSelf(self);
  if (!dataElement)
    return nullptr;

  CefPostDataElement::Type type = dataElement->GetType();
  jobject jtype = nullptr;
  switch (type) {
    JNI_CASE(env, "org/cef/network/CefPostDataElement$Type", PDE_TYPE_EMPTY, jtype);
    JNI_CASE(env, "org/cef/network/CefPostDataElement$Type", PDE_TYPE_BYTES, jtype);
    JNI_CASE(env, "org/cef/network/CefPostDataElement$Type", PDE_TYPE_FILE, jtype);
    JNI_CASE(env, "org/cef/network/CefPostDataElement$Type", PDE_TYPE_NUM_VALUES, jtype);
  }
  return jtype;
}

JNIEXPORT jstring JNICALL Java_org_cef_network_CefPostDataElement_1N_N_1GetFile(JNIEnv* env, jobject obj, jlong self) {
  CefRefPtr<CefPostDataElement> dataElement = GetSelf(self);
  if (!dataElement)
    return nullptr;
  return NewJNIString(env, dataElement->GetFile());
}

JNIEXPORT jint JNICALL Java_org_cef_network_CefPostDataElement_1N_N_1GetBytesCount(JNIEnv* env, jobject obj, jlong self) {
  CefRefPtr<CefPostDataElement> dataElement = GetSelf(self);
  if (!dataElement)
    return 0;
  return SaturateSizeToJInt(dataElement->GetBytesCount());
}

JNIEXPORT jint JNICALL Java_org_cef_network_CefPostDataElement_1N_N_1GetBytes(JNIEnv* env, jobject obj, jlong self, jint jsize, jbyteArray jbytes) {
  CefRefPtr<CefPostDataElement> dataElement = GetSelf(self);
  if (!dataElement)
    return 0;

  size_t size;
  if (!GetValidatedByteArraySize(env, jbytes, jsize, &size))
    return 0;
  if (size == 0)
    return 0;
  jbyte* bytes = env->GetByteArrayElements(jbytes, nullptr);
  if (!bytes)
    return 0;
  std::memset(bytes, 0, size);
  const size_t read_length = dataElement->GetBytes(size, bytes);
  env->ReleaseByteArrayElements(jbytes, bytes, 0);
  return SaturateSizeToJInt(read_length);
}
