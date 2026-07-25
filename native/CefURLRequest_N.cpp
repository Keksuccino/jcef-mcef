// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "CefURLRequest_N.h"

#include "include/cef_request_context.h"

#include "jni_scoped_helpers.h"
#include "url_request.h"

namespace {

void ThrowDisposedContextException(JNIEnv* env) {
  if (env->ExceptionCheck())
    return;
  ScopedJNIClass exception_class(env, "java/lang/IllegalStateException");
  if (exception_class)
    env->ThrowNew(exception_class, "CefRequestContext is disposed");
}

}  // namespace

JNIEXPORT void JNICALL Java_org_cef_network_CefURLRequest_1N_N_1Create(JNIEnv* env, jobject obj, jobject jrequest, jobject jrequest_client) {
  URLRequestAdmission admission = AcquireURLRequestCreationAdmission();
  if (!admission || !jrequest || !jrequest_client)
    return;
  CreateStandaloneURLRequest(env, obj, jrequest, jrequest_client, nullptr, admission);
}

JNIEXPORT void JNICALL Java_org_cef_network_CefURLRequest_1N_N_1CreateWithContext(JNIEnv* env, jobject obj, jobject jrequest, jobject jrequest_client, jobject jrequest_context) {
  URLRequestAdmission admission = AcquireURLRequestCreationAdmission();
  if (!admission || !jrequest || !jrequest_client || !jrequest_context)
    return;

  // CefRequestContext_N exposes a raw handle. The Java caller holds this
  // context's monitor across JNI entry, and this must be the first native
  // conversion so CefRequestContext_N.dispose() cannot release the last
  // reference before CefRefPtr takes ownership.
  CefRefPtr<CefRequestContext> request_context = GetCefFromJNIObject<CefRequestContext>(env, jrequest_context, "CefRequestContext");
  if (!request_context) {
    ThrowDisposedContextException(env);
    return;
  }

  CreateStandaloneURLRequest(env, obj, jrequest, jrequest_client, request_context, admission);
}

JNIEXPORT void JNICALL Java_org_cef_network_CefURLRequest_1N_N_1Dispose(JNIEnv* env, jobject obj, jlong self) {
  DisposeURLRequest(env, obj, self);
}

JNIEXPORT jobject JNICALL Java_org_cef_network_CefURLRequest_1N_N_1GetRequestStatus(JNIEnv* env, jobject obj, jlong self) {
  URLRequestAccess url_request = AcquireURLRequestAccess(self);
  if (!url_request)
    return nullptr;

  ScopedJNIURLRequestStatus status(env, url_request->GetRequestStatus());
  return status.Release();
}

JNIEXPORT jint JNICALL Java_org_cef_network_CefURLRequest_1N_N_1GetRequestErrorCode(JNIEnv* env, jobject obj, jlong self) {
  URLRequestAccess url_request = AcquireURLRequestAccess(self);
  cef_errorcode_t error = url_request ? url_request->GetRequestError() : ERR_FAILED;
  static_assert(sizeof(cef_errorcode_t) == sizeof(jint));
  return static_cast<jint>(error);
}

JNIEXPORT jobject JNICALL Java_org_cef_network_CefURLRequest_1N_N_1GetResponse(JNIEnv* env, jobject obj, jlong self) {
  URLRequestAccess url_request = AcquireURLRequestAccess(self);
  if (!url_request)
    return nullptr;

  CefRefPtr<CefResponse> response = url_request->GetResponse();
  if (!response)
    return nullptr;

  ScopedJNIResponse jresponse(env, response);
  return jresponse.Release();
}

JNIEXPORT jboolean JNICALL Java_org_cef_network_CefURLRequest_1N_N_1ResponseWasCached(JNIEnv* env, jobject obj, jlong self) {
  URLRequestAccess url_request = AcquireURLRequestAccess(self);
  return url_request && url_request->ResponseWasCached() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_org_cef_network_CefURLRequest_1N_N_1Cancel(JNIEnv* env, jobject obj, jlong self) {
  URLRequestAccess url_request = AcquireURLRequestAccess(self);
  if (url_request)
    url_request->Cancel();
}

JNIEXPORT jboolean JNICALL Java_org_cef_network_CefURLRequest_1N_N_1RunDisposedCreationRaceForTesting(JNIEnv* env, jclass cls, jobject jurl_request) {
  return RunDisposedCreationRaceForTesting(env, jurl_request) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_org_cef_network_CefURLRequest_1N_N_1RunTokenRegistryConcurrencyForTesting(JNIEnv* env, jclass cls) {
  return RunTokenRegistryConcurrencyForTesting() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_org_cef_network_CefURLRequest_1N_N_1RunPendingDispatchAbandonmentForTesting(JNIEnv* env, jclass cls) {
  return RunPendingDispatchAbandonmentForTesting() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_org_cef_network_CefURLRequest_1N_N_1RunURLRequestLifecycleForTesting(JNIEnv* env, jclass cls, jobject jurl_request) {
  return RunURLRequestLifecycleForTesting(env, jurl_request) ? JNI_TRUE : JNI_FALSE;
}
