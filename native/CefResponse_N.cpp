// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "CefResponse_N.h"

#include "include/cef_response.h"

#include "jni_scoped_helpers.h"
#include "jni_util.h"
#include "network_jni_util.h"

namespace {

const char kCefClassName[] = "CefResponse";

CefRefPtr<CefResponse> GetSelf(jlong self) {
  return reinterpret_cast<CefResponse*>(self);
}

}  // namespace

JNIEXPORT jobject JNICALL
Java_org_cef_network_CefResponse_1N_N_1Create(JNIEnv* env, jclass cls) {
  CefRefPtr<CefResponse> response = CefResponse::Create();
  ScopedJNIResponse jresponse(env, response);
  return jresponse.Release();
}

JNIEXPORT void JNICALL
Java_org_cef_network_CefResponse_1N_N_1Dispose(JNIEnv* env,
                                               jobject obj,
                                               jlong self) {
  SetCefForJNIObject<CefResponse>(env, obj, nullptr, kCefClassName);
}

JNIEXPORT jboolean JNICALL
Java_org_cef_network_CefResponse_1N_N_1IsReadOnly(JNIEnv* env,
                                                  jobject obj,
                                                  jlong self) {
  CefRefPtr<CefResponse> response = GetSelf(self);
  if (!response)
    return JNI_FALSE;
  return response->IsReadOnly() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_org_cef_network_CefResponse_1N_N_1GetErrorCode(JNIEnv* env,
                                                    jobject obj,
                                                    jlong self) {
  CefRefPtr<CefResponse> response = GetSelf(self);
  if (!response)
    return ERR_NONE;
  static_assert(sizeof(cef_errorcode_t) == sizeof(jint));
  return static_cast<jint>(response->GetError());
}

JNIEXPORT void JNICALL
Java_org_cef_network_CefResponse_1N_N_1SetErrorCode(JNIEnv* env,
                                                    jobject obj,
                                                    jlong self,
                                                    jint jerror_code) {
  CefRefPtr<CefResponse> response = GetSelf(self);
  if (!response)
    return;
  static_assert(sizeof(cef_errorcode_t) == sizeof(jint));
  response->SetError(static_cast<cef_errorcode_t>(jerror_code));
}

JNIEXPORT jint JNICALL
Java_org_cef_network_CefResponse_1N_N_1GetStatus(JNIEnv* env,
                                                 jobject obj,
                                                 jlong self) {
  CefRefPtr<CefResponse> response = GetSelf(self);
  if (!response)
    return 0;
  return response->GetStatus();
}

JNIEXPORT void JNICALL
Java_org_cef_network_CefResponse_1N_N_1SetStatus(JNIEnv* env,
                                                 jobject obj,
                                                 jlong self,
                                                 jint jstatus) {
  CefRefPtr<CefResponse> response = GetSelf(self);
  if (!response)
    return;
  static_assert(sizeof(jint) == sizeof(int));
  response->SetStatus(static_cast<int>(jstatus));
}

JNIEXPORT jstring JNICALL
Java_org_cef_network_CefResponse_1N_N_1GetStatusText(JNIEnv* env,
                                                     jobject obj,
                                                     jlong self) {
  CefRefPtr<CefResponse> response = GetSelf(self);
  if (!response)
    return nullptr;
  return NewJNIString(env, response->GetStatusText());
}

JNIEXPORT void JNICALL
Java_org_cef_network_CefResponse_1N_N_1SetStatusText(JNIEnv* env,
                                                     jobject obj,
                                                     jlong self,
                                                     jstring jstatus) {
  CefRefPtr<CefResponse> response = GetSelf(self);
  if (!response)
    return;
  response->SetStatusText(GetJNIString(env, jstatus));
}

JNIEXPORT jstring JNICALL
Java_org_cef_network_CefResponse_1N_N_1GetMimeType(JNIEnv* env,
                                                   jobject obj,
                                                   jlong self) {
  CefRefPtr<CefResponse> response = GetSelf(self);
  if (!response)
    return nullptr;
  return NewJNIString(env, response->GetMimeType());
}

JNIEXPORT void JNICALL
Java_org_cef_network_CefResponse_1N_N_1SetMimeType(JNIEnv* env,
                                                   jobject obj,
                                                   jlong self,
                                                   jstring jmimeType) {
  CefRefPtr<CefResponse> response = GetSelf(self);
  if (!response)
    return;
  response->SetMimeType(GetJNIString(env, jmimeType));
}

JNIEXPORT jstring JNICALL
Java_org_cef_network_CefResponse_1N_N_1GetCharset(JNIEnv* env,
                                                  jobject obj,
                                                  jlong self) {
  CefRefPtr<CefResponse> response = GetSelf(self);
  if (!response)
    return nullptr;
  return NewJNIString(env, response->GetCharset());
}

JNIEXPORT void JNICALL
Java_org_cef_network_CefResponse_1N_N_1SetCharset(JNIEnv* env,
                                                  jobject obj,
                                                  jlong self,
                                                  jstring jcharset) {
  CefRefPtr<CefResponse> response = GetSelf(self);
  if (!response)
    return;
  response->SetCharset(GetJNIString(env, jcharset));
}

JNIEXPORT jstring JNICALL
Java_org_cef_network_CefResponse_1N_N_1GetHeaderByName(JNIEnv* env,
                                                       jobject obj,
                                                       jlong self,
                                                       jstring jname) {
  CefRefPtr<CefResponse> response = GetSelf(self);
  if (!response)
    return nullptr;
  return NewJNIString(env, response->GetHeaderByName(GetJNIString(env, jname)));
}

JNIEXPORT void JNICALL
Java_org_cef_network_CefResponse_1N_N_1SetHeaderByName(JNIEnv* env,
                                                       jobject obj,
                                                       jlong self,
                                                       jstring jname,
                                                       jstring jvalue,
                                                       jboolean joverride) {
  CefRefPtr<CefResponse> response = GetSelf(self);
  if (!response)
    return;
  return response->SetHeaderByName(GetJNIString(env, jname),
                                   GetJNIString(env, jvalue),
                                   joverride != JNI_FALSE);
}

JNIEXPORT void JNICALL
Java_org_cef_network_CefResponse_1N_N_1GetHeaderMap(JNIEnv* env,
                                                    jobject obj,
                                                    jlong self,
                                                    jobject jheaderMap) {
  CefRefPtr<CefResponse> response = GetSelf(self);
  if (!response)
    return;

  CefResponse::HeaderMap headerMap;
  response->GetHeaderMap(headerMap);
  SetJNIStringMultiMap(env, jheaderMap, headerMap);
}

JNIEXPORT void JNICALL
Java_org_cef_network_CefResponse_1N_N_1SetHeaderMap(JNIEnv* env,
                                                    jobject obj,
                                                    jlong self,
                                                    jobject jheaderMap) {
  CefRefPtr<CefResponse> response = GetSelf(self);
  if (!response)
    return;

  CefResponse::HeaderMap headerMap;
  GetJNIStringMultiMap(env, jheaderMap, headerMap);
  response->SetHeaderMap(headerMap);
}

JNIEXPORT jobjectArray JNICALL
Java_org_cef_network_CefResponse_1N_N_1GetHeaderList(JNIEnv* env,
                                                     jobject obj,
                                                     jlong self) {
  CefRefPtr<CefResponse> response = GetSelf(self);
  if (!response)
    return nullptr;
  CefResponse::HeaderMap header_map;
  response->GetHeaderMap(header_map);
  return NewJNIHeaderPairs(env, header_map);
}

JNIEXPORT void JNICALL Java_org_cef_network_CefResponse_1N_N_1SetHeaderList(
    JNIEnv* env,
    jobject obj,
    jlong self,
    jobjectArray jheader_pairs) {
  CefRefPtr<CefResponse> response = GetSelf(self);
  if (!response)
    return;
  CefResponse::HeaderMap header_map;
  if (!GetJNIHeaderPairs(env, jheader_pairs, header_map))
    return;
  response->SetHeaderMap(header_map);
}

JNIEXPORT jstring JNICALL
Java_org_cef_network_CefResponse_1N_N_1GetURL(JNIEnv* env,
                                              jobject obj,
                                              jlong self) {
  CefRefPtr<CefResponse> response = GetSelf(self);
  if (!response)
    return nullptr;
  return NewJNIString(env, response->GetURL());
}

JNIEXPORT void JNICALL
Java_org_cef_network_CefResponse_1N_N_1SetURL(JNIEnv* env,
                                              jobject obj,
                                              jlong self,
                                              jstring jurl) {
  CefRefPtr<CefResponse> response = GetSelf(self);
  if (!response)
    return;
  response->SetURL(GetJNIString(env, jurl));
}
