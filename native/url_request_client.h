// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#ifndef JCEF_NATIVE_URL_REQUEST_CLIENT_H_
#define JCEF_NATIVE_URL_REQUEST_CLIENT_H_
#pragma once

#include <jni.h>
#include <mutex>

#include "include/cef_urlrequest.h"

#include "jni_scoped_helpers.h"

// CefURLRequestClient implementation.
class URLRequestClient : public CefURLRequestClient {
 private:
  URLRequestClient(JNIEnv* env, jobject jURLRequestClient, jobject jURLRequest);

 public:
  static CefRefPtr<URLRequestClient> Create(JNIEnv* env, jobject jRequestClient, jobject jURLRequest);
  virtual ~URLRequestClient() = default;

  // CefURLRequestClient methods
  virtual void OnRequestComplete(CefRefPtr<CefURLRequest> request) override;

  virtual void OnUploadProgress(CefRefPtr<CefURLRequest> request, int64_t current, int64_t total) override;

  virtual void OnDownloadProgress(CefRefPtr<CefURLRequest> request, int64_t current, int64_t total) override;

  virtual void OnDownloadData(CefRefPtr<CefURLRequest> request, const void* data, size_t data_length) override;

  virtual bool GetAuthCredentials(bool isProxy, const CefString& host, int port, const CefString& realm, const CefString& scheme, CefRefPtr<CefAuthCallback> callback) override;

 private:
  bool CreateLocalRefsLocked(JNIEnv* env, jobject* jURLRequestClient, jobject* jURLRequest);
  bool SnapshotJavaHandles(JNIEnv* env, jobject* jURLRequestClient, jobject* jURLRequest);
  bool CompleteJavaHandles(JNIEnv* env, jobject* jURLRequestClient, jobject* jURLRequest);

  // CEF may post GetAuthCredentials to IO with a copied client reference while
  // completion runs on UI. This mutex serializes the terminal transition with
  // JNI global-to-local snapshots but is never held while calling Java.
  // Keep it declared before every guarded field so reverse destruction keeps
  // the mutex alive until both globals have been destroyed.
  std::mutex java_handles_lock_;
  bool completed_ = false;
  ScopedJNIObjectGlobal client_handle_;
  ScopedJNIObjectGlobal request_handle_;

  // Include the default reference counting implementation.
  IMPLEMENT_REFCOUNTING(URLRequestClient);
};

#endif  // JCEF_NATIVE_URL_REQUEST_CLIENT_H_
