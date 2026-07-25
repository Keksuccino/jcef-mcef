// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#ifndef JCEF_NATIVE_URL_REQUEST_H_
#define JCEF_NATIVE_URL_REQUEST_H_
#pragma once

#include <jni.h>

#include <utility>

#include "include/cef_frame.h"
#include "include/cef_request.h"
#include "include/cef_request_context.h"
#include "include/cef_task.h"
#include "include/cef_urlrequest.h"

#include "url_request_client.h"

class URLRequestOperation;
class URLRequestLifecycle;

// Keeps the native URLRequest runtime open across a complete Java/JNI access.
// The lifecycle barrier waits for every admission before unloading CEF, so the
// guard must be acquired before converting any Java wrapper backed by a CEF
// pointer and released only after all resulting CefRefPtrs are gone.
class URLRequestAdmission {
 public:
  URLRequestAdmission() = default;
  URLRequestAdmission(URLRequestAdmission&& other) noexcept;
  URLRequestAdmission& operator=(URLRequestAdmission&& other) noexcept;
  ~URLRequestAdmission();

  explicit operator bool() const { return lifecycle_ != nullptr; }

 private:
  friend class URLRequestLifecycle;
  friend class URLRequestAccess;

  explicit URLRequestAdmission(URLRequestLifecycle* lifecycle) : lifecycle_(lifecycle) {}
  void Release();

  URLRequestLifecycle* lifecycle_ = nullptr;

  DISALLOW_COPY_AND_ASSIGN(URLRequestAdmission);
};

// Owns the native request and marshals all CEF access to the sequence where it
// was created. Standalone and frame-associated Java APIs deliberately share
// this owner so callback identity, cancellation and disposal semantics cannot
// diverge between the two creation paths.
class URLRequest : public CefBaseRefCounted {
 public:
  URLRequest(CefThreadId thread_id, CefRefPtr<CefRequest> request, CefRefPtr<URLRequestClient> client, CefRefPtr<CefRequestContext> request_context, CefRefPtr<CefFrame> frame);

  bool Create();
  CefURLRequest::Status GetRequestStatus();
  CefURLRequest::ErrorCode GetRequestError();
  CefRefPtr<CefResponse> GetResponse();
  bool ResponseWasCached();
  void Cancel();

 private:
  friend class URLRequestOperation;

  CefThreadId thread_id_;
  CefRefPtr<CefRequest> request_;
  CefRefPtr<URLRequestClient> client_;
  // Keep explicit association objects alive while creation is marshaled to
  // TID_UI. A successful CefURLRequest takes its own references for the
  // asynchronous request lifetime.
  CefRefPtr<CefRequestContext> request_context_;
  CefRefPtr<CefFrame> frame_;
  CefRefPtr<CefURLRequest> url_request_;

  IMPLEMENT_REFCOUNTING(URLRequest);
};

// Owns both the admitted runtime access and the retained request. Destruction
// explicitly drops the CefRefPtr before ending admission so shutdown cannot
// unload CEF between those two actions.
class URLRequestAccess {
 public:
  URLRequestAccess() = default;
  URLRequestAccess(URLRequestAccess&& other) noexcept;
  URLRequestAccess& operator=(URLRequestAccess&& other) noexcept;
  ~URLRequestAccess();

  explicit operator bool() const { return owner_.get() != nullptr; }
  URLRequest* operator->() const { return owner_.get(); }

 private:
  friend class URLRequestLifecycle;

  URLRequestAccess(URLRequestAdmission admission, CefRefPtr<URLRequest> owner) : admission_(std::move(admission)), owner_(owner) {}
  void Release();

  URLRequestAdmission admission_;
  CefRefPtr<URLRequest> owner_;

  DISALLOW_COPY_AND_ASSIGN(URLRequestAccess);
};

URLRequestAdmission AcquireURLRequestCreationAdmission();
URLRequestAccess AcquireURLRequestAccess(jlong token);
void OpenURLRequestLifecycle();
void CloseURLRequestLifecycle();
void DisposeURLRequest(JNIEnv* env, jobject jurl_request, jlong token);
bool CreateStandaloneURLRequest(JNIEnv* env, jobject jurl_request, jobject jrequest, jobject jrequest_client, CefRefPtr<CefRequestContext> request_context, URLRequestAdmission& admission);
jobject CreateFrameURLRequest(JNIEnv* env, jobject jrequest, jobject jrequest_client, CefRefPtr<CefFrame> frame, URLRequestAdmission& admission);
bool RunDisposedCreationRaceForTesting(JNIEnv* env, jobject jurl_request);
bool RunTokenRegistryConcurrencyForTesting();
bool RunPendingDispatchAbandonmentForTesting();
bool RunURLRequestLifecycleForTesting(JNIEnv* env, jobject jurl_request);

#endif  // JCEF_NATIVE_URL_REQUEST_H_
