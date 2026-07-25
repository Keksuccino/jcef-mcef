// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "CefURLRequest_N.h"

#include "include/cef_request.h"
#include "include/cef_task.h"
#include "include/cef_urlrequest.h"

#include "critical_wait.h"
#include "jni_scoped_helpers.h"
#include "jni_util.h"
#include "url_request_client.h"

namespace {
class URLRequestOperation;

class URLRequest : public CefBaseRefCounted {
 public:
  URLRequest(CefThreadId thread_id, CefRefPtr<CefRequest> request, CefRefPtr<URLRequestClient> client) : thread_id_(thread_id), request_(request), client_(client) {}

  bool Create();
  CefURLRequest::Status GetRequestStatus();
  CefURLRequest::ErrorCode GetRequestError();
  CefRefPtr<CefResponse> GetResponse();
  void Cancel();

 private:
  friend class URLRequestOperation;

  CefThreadId thread_id_;
  CefRefPtr<CefRequest> request_;
  CefRefPtr<URLRequestClient> client_;
  CefRefPtr<CefURLRequest> url_request_;

  IMPLEMENT_REFCOUNTING(URLRequest);
};

// A URLRequest can be called concurrently from Java and CEF operations may
// synchronously reenter Java. Each dispatch therefore owns independent mode,
// result and wait state, and never holds its completion lock across CEF calls.
class URLRequestOperation : public CefTask {
 public:
  enum Mode {
    REQ_CREATE,
    REQ_STATUS,
    REQ_ERROR,
    REQ_RESPONSE,
    REQ_CANCEL,
  };

  URLRequestOperation(CefRefPtr<URLRequest> owner, Mode mode) : owner_(owner), mode_(mode), wait_condition_(&completion_lock_) {}

  bool Dispatch(CefThreadId thread_id) {
    if (CefCurrentlyOn(thread_id)) {
      Execute();
      return true;
    }

    completion_lock_.Lock();
    if (!CefPostTask(thread_id, this)) {
      completion_lock_.Unlock();
      return false;
    }
    while (!completed_)
      wait_condition_.Wait();
    completion_lock_.Unlock();
    return true;
  }

  bool created() const { return created_; }
  CefURLRequest::Status status() const { return status_; }
  CefURLRequest::ErrorCode error() const { return error_; }
  CefRefPtr<CefResponse> response() const { return response_; }

  void Execute() override {
    switch (mode_) {
      case REQ_CREATE:
        // TODO(JCEF): Add the ability to specify a CefRequestContext.
        if (!owner_->url_request_)
          owner_->url_request_ = CefURLRequest::Create(owner_->request_, owner_->client_.get(), nullptr);
        created_ = owner_->url_request_.get() != nullptr;
        break;
      case REQ_STATUS:
        if (owner_->url_request_)
          status_ = owner_->url_request_->GetRequestStatus();
        break;
      case REQ_ERROR:
        if (owner_->url_request_)
          error_ = owner_->url_request_->GetRequestError();
        break;
      case REQ_RESPONSE:
        if (owner_->url_request_)
          response_ = owner_->url_request_->GetResponse();
        break;
      case REQ_CANCEL:
        // Completion may reenter cancel on TID_UI. Only a pending request can
        // be canceled, preventing a terminal reentrant cancel from recursing.
        if (owner_->url_request_ && owner_->url_request_->GetRequestStatus() == UR_IO_PENDING)
          owner_->url_request_->Cancel();
        break;
    }

    completion_lock_.Lock();
    completed_ = true;
    wait_condition_.WakeUp();
    completion_lock_.Unlock();
  }

 private:
  CefRefPtr<URLRequest> owner_;
  const Mode mode_;
  CriticalLock completion_lock_;
  CriticalWait wait_condition_;
  bool completed_ = false;
  bool created_ = false;
  CefURLRequest::Status status_ = UR_UNKNOWN;
  CefURLRequest::ErrorCode error_ = ERR_FAILED;
  CefRefPtr<CefResponse> response_;

  IMPLEMENT_REFCOUNTING(URLRequestOperation);
};

bool URLRequest::Create() {
  CefRefPtr<URLRequestOperation> operation = new URLRequestOperation(this, URLRequestOperation::REQ_CREATE);
  return operation->Dispatch(thread_id_) && operation->created();
}

CefURLRequest::Status URLRequest::GetRequestStatus() {
  CefRefPtr<URLRequestOperation> operation = new URLRequestOperation(this, URLRequestOperation::REQ_STATUS);
  return operation->Dispatch(thread_id_) ? operation->status() : UR_UNKNOWN;
}

CefURLRequest::ErrorCode URLRequest::GetRequestError() {
  CefRefPtr<URLRequestOperation> operation = new URLRequestOperation(this, URLRequestOperation::REQ_ERROR);
  return operation->Dispatch(thread_id_) ? operation->error() : ERR_FAILED;
}

CefRefPtr<CefResponse> URLRequest::GetResponse() {
  CefRefPtr<URLRequestOperation> operation = new URLRequestOperation(this, URLRequestOperation::REQ_RESPONSE);
  return operation->Dispatch(thread_id_) ? operation->response() : nullptr;
}

void URLRequest::Cancel() {
  CefRefPtr<URLRequestOperation> operation = new URLRequestOperation(this, URLRequestOperation::REQ_CANCEL);
  operation->Dispatch(thread_id_);
}

const char kCefClassName[] = "CefURLRequest";

CefRefPtr<URLRequest> GetSelf(jlong self) {
  return reinterpret_cast<URLRequest*>(self);
}

}  // namespace

JNIEXPORT void JNICALL
Java_org_cef_network_CefURLRequest_1N_N_1Create(JNIEnv* env,
                                                jobject obj,
                                                jobject jrequest,
                                                jobject jRequestClient) {
  if (!jrequest || !jRequestClient)
    return;

  ScopedJNIRequest requestObj(env);
  requestObj.SetHandle(jrequest, false /* should_delete */);
  CefRefPtr<CefRequest> request = requestObj.GetCefObject();
  if (!request)
    return;

  CefRefPtr<URLRequestClient> client =
      URLRequestClient::Create(env, jRequestClient, obj);

  CefRefPtr<URLRequest> urlRequest = new URLRequest(TID_UI, request, client);
  if (!urlRequest->Create())
    return;
  SetCefForJNIObject(env, obj, urlRequest.get(), kCefClassName);
}

JNIEXPORT void JNICALL
Java_org_cef_network_CefURLRequest_1N_N_1Dispose(JNIEnv* env,
                                                 jobject obj,
                                                 jlong self) {
  SetCefForJNIObject<URLRequest>(env, obj, nullptr, kCefClassName);
}

JNIEXPORT jobject JNICALL
Java_org_cef_network_CefURLRequest_1N_N_1GetRequestStatus(JNIEnv* env,
                                                          jobject obj,
                                                          jlong self) {
  CefRefPtr<URLRequest> urlRequest = GetSelf(self);
  if (!urlRequest)
    return nullptr;

  ScopedJNIURLRequestStatus status(env, urlRequest->GetRequestStatus());
  return status.Release();
}

JNIEXPORT jint JNICALL
Java_org_cef_network_CefURLRequest_1N_N_1GetRequestErrorCode(JNIEnv* env,
                                                             jobject obj,
                                                             jlong self) {
  CefRefPtr<URLRequest> urlRequest = GetSelf(self);
  cef_errorcode_t err = ERR_FAILED;
  if (urlRequest)
    err = urlRequest->GetRequestError();
  static_assert(sizeof(cef_errorcode_t) == sizeof(jint));
  return static_cast<jint>(err);
}

JNIEXPORT jobject JNICALL
Java_org_cef_network_CefURLRequest_1N_N_1GetResponse(JNIEnv* env,
                                                     jobject obj,
                                                     jlong self) {
  CefRefPtr<URLRequest> urlRequest = GetSelf(self);
  if (!urlRequest)
    return nullptr;

  CefRefPtr<CefResponse> response = urlRequest->GetResponse();
  if (!response)
    return nullptr;

  ScopedJNIResponse jresponse(env, response);
  return jresponse.Release();
}

JNIEXPORT void JNICALL
Java_org_cef_network_CefURLRequest_1N_N_1Cancel(JNIEnv* env,
                                                jobject obj,
                                                jlong self) {
  CefRefPtr<URLRequest> urlRequest = GetSelf(self);
  if (!urlRequest)
    return;
  urlRequest->Cancel();
}
