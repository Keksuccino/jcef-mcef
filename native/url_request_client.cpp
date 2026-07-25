// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "url_request_client.h"

#include <limits>

#include "jni_util.h"
#include "util.h"

namespace {

bool DescribeAndClearJNIException(JNIEnv* env) {
  if (!env->ExceptionCheck())
    return false;
  env->ExceptionDescribe();
  env->ExceptionClear();
  return true;
}

void CancelPendingRequest(CefRefPtr<CefURLRequest> request) {
  // JNI conversion failures make the current response chunk undeliverable.
  // Cancel instead of reporting success with a silently corrupted Java body.
  if (request && request->GetRequestStatus() == UR_IO_PENDING)
    request->Cancel();
}

}  // namespace

URLRequestClient::URLRequestClient(JNIEnv* env, jobject jURLRequestClient, jobject jURLRequest)
    : client_handle_(env, jURLRequestClient), request_handle_(env, jURLRequest) {}

CefRefPtr<URLRequestClient> URLRequestClient::Create(JNIEnv* env, jobject jURLRequestClient, jobject jURLRequest) {
  // A Java client can serve multiple simultaneous requests. Each request must
  // therefore own a wrapper with its own Java CefURLRequest identity instead
  // of caching one wrapper in the client's CefNative slot.
  return new URLRequestClient(env, jURLRequestClient, jURLRequest);
}

void URLRequestClient::OnRequestComplete(CefRefPtr<CefURLRequest> request) {
  ScopedJNIEnv env;
  if (!env)
    return;

  jobject jclient = nullptr;
  jobject jrequest = nullptr;
  if (!CompleteJavaHandles(env, &jclient, &jrequest))
    return;
  ScopedJNIObjectLocal client(env, jclient);
  ScopedJNIObjectLocal request_snapshot(env, jrequest);

  // Globals have already been released. These locals retain both Java objects
  // while completion reenters terminal status/response/dispose operations.
  JNI_CALL_VOID_METHOD(env, client, "onRequestComplete", "(Lorg/cef/network/CefURLRequest;)V", request_snapshot.get());
}

void URLRequestClient::OnUploadProgress(CefRefPtr<CefURLRequest> request, int64_t current, int64_t total) {
  ScopedJNIEnv env;
  if (!env)
    return;

  jobject jclient = nullptr;
  jobject jrequest = nullptr;
  if (!SnapshotJavaHandles(env, &jclient, &jrequest))
    return;
  ScopedJNIObjectLocal client(env, jclient);
  ScopedJNIObjectLocal request_snapshot(env, jrequest);
  JNI_CALL_VOID_METHOD(env, client, "onUploadProgress", "(Lorg/cef/network/CefURLRequest;JJ)V", request_snapshot.get(), static_cast<jlong>(current), static_cast<jlong>(total));
}

void URLRequestClient::OnDownloadProgress(CefRefPtr<CefURLRequest> request, int64_t current, int64_t total) {
  ScopedJNIEnv env;
  if (!env)
    return;

  jobject jclient = nullptr;
  jobject jrequest = nullptr;
  if (!SnapshotJavaHandles(env, &jclient, &jrequest))
    return;
  ScopedJNIObjectLocal client(env, jclient);
  ScopedJNIObjectLocal request_snapshot(env, jrequest);
  JNI_CALL_VOID_METHOD(env, client, "onDownloadProgress", "(Lorg/cef/network/CefURLRequest;JJ)V", request_snapshot.get(), static_cast<jlong>(current), static_cast<jlong>(total));
}

void URLRequestClient::OnDownloadData(CefRefPtr<CefURLRequest> request, const void* data, size_t data_length) {
  if (data_length > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
    LOG(ERROR)
        << "CefURLRequest download chunk exceeds the JNI byte-array limit: "
        << data_length;
    CancelPendingRequest(request);
    return;
  }
  if (data_length != 0 && data == nullptr) {
    LOG(ERROR)
        << "CefURLRequest supplied null download data for a non-empty chunk";
    CancelPendingRequest(request);
    return;
  }

  ScopedJNIEnv env;
  if (!env) {
    CancelPendingRequest(request);
    return;
  }

  jobject jclient = nullptr;
  jobject jrequest = nullptr;
  if (!SnapshotJavaHandles(env, &jclient, &jrequest)) {
    CancelPendingRequest(request);
    return;
  }
  ScopedJNIObjectLocal client(env, jclient);
  ScopedJNIObjectLocal request_snapshot(env, jrequest);

  const jsize jdata_length = static_cast<jsize>(data_length);
  jbyteArray jdata = env->NewByteArray(jdata_length);
  const bool allocation_exception = DescribeAndClearJNIException(env);
  if (!jdata || allocation_exception) {
    if (jdata)
      env->DeleteLocalRef(jdata);
    LOG(ERROR)
        << "Failed to allocate CefURLRequest download byte array of length "
        << data_length;
    CancelPendingRequest(request);
    return;
  }
  if (jdata_length > 0) {
    env->SetByteArrayRegion(jdata, 0, jdata_length, reinterpret_cast<const jbyte*>(data));
    if (DescribeAndClearJNIException(env)) {
      env->DeleteLocalRef(jdata);
      LOG(ERROR) << "Failed to copy CefURLRequest download data of length "
                 << data_length;
      CancelPendingRequest(request);
      return;
    }
  }

  JNI_CALL_VOID_METHOD(env, client, "onDownloadData", "(Lorg/cef/network/CefURLRequest;[BI)V", request_snapshot.get(), jdata, static_cast<jint>(jdata_length));

  env->DeleteLocalRef(jdata);
}

bool URLRequestClient::GetAuthCredentials(bool isProxy, const CefString& host, int port, const CefString& realm, const CefString& scheme, CefRefPtr<CefAuthCallback> callback) {
  jboolean jresult = JNI_FALSE;

  ScopedJNIEnv env;
  if (!env)
    return false;

  jobject jclient = nullptr;
  jobject jrequest = nullptr;
  if (!SnapshotJavaHandles(env, &jclient, &jrequest))
    return false;
  ScopedJNIObjectLocal client(env, jclient);
  // The Java auth signature has no request parameter. Retaining this otherwise
  // unused local keeps the request alive if completion clears both globals
  // while this IO-thread callback is still in flight.
  ScopedJNIObjectLocal request_snapshot(env, jrequest);

  ScopedJNIString jhost(env, host);
  ScopedJNIString jrealm(env, realm);
  ScopedJNIString jscheme(env, scheme);
  ScopedJNIAuthCallback jcallback(env, callback);

  JNI_CALL_METHOD(env, client, "getAuthCredentials", "(ZLjava/lang/String;ILjava/lang/String;Ljava/lang/String;Lorg/cef/callback/CefAuthCallback;)Z", Boolean, jresult, (isProxy ? JNI_TRUE : JNI_FALSE), jhost.get(), port, jrealm.get(), jscheme.get(), jcallback.get());

  if (jresult == JNI_FALSE) {
    // If the Java method returns "false" the callback won't be used and
    // the reference can therefore be removed.
    jcallback.SetTemporary();
  }

  return (jresult != JNI_FALSE);
}

bool URLRequestClient::CreateLocalRefsLocked(JNIEnv* env, jobject* jURLRequestClient, jobject* jURLRequest) {
  *jURLRequestClient = nullptr;
  *jURLRequest = nullptr;

  *jURLRequestClient = env->NewLocalRef(client_handle_.get());
  const bool client_snapshot_exception = DescribeAndClearJNIException(env);
  if (!*jURLRequestClient || client_snapshot_exception) {
    if (*jURLRequestClient)
      env->DeleteLocalRef(*jURLRequestClient);
    *jURLRequestClient = nullptr;
    LOG(ERROR) << "Failed to snapshot the CefURLRequest Java client";
    return false;
  }

  *jURLRequest = env->NewLocalRef(request_handle_.get());
  const bool request_snapshot_exception = DescribeAndClearJNIException(env);
  if (!*jURLRequest || request_snapshot_exception) {
    if (*jURLRequest)
      env->DeleteLocalRef(*jURLRequest);
    env->DeleteLocalRef(*jURLRequestClient);
    *jURLRequestClient = nullptr;
    *jURLRequest = nullptr;
    LOG(ERROR) << "Failed to snapshot the Java CefURLRequest";
    return false;
  }
  return true;
}

bool URLRequestClient::SnapshotJavaHandles(JNIEnv* env, jobject* jURLRequestClient, jobject* jURLRequest) {
  std::lock_guard<std::mutex> lock(java_handles_lock_);
  if (completed_)
    return false;
  return CreateLocalRefsLocked(env, jURLRequestClient, jURLRequest);
}

bool URLRequestClient::CompleteJavaHandles(JNIEnv* env, jobject* jURLRequestClient, jobject* jURLRequest) {
  std::lock_guard<std::mutex> lock(java_handles_lock_);
  if (completed_)
    return false;

  completed_ = true;
  const bool snapshot_created = CreateLocalRefsLocked(env, jURLRequestClient, jURLRequest);
  request_handle_.Clear(env);
  client_handle_.Clear(env);
  return snapshot_created;
}
