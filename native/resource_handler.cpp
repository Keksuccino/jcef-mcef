// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "resource_handler.h"

#include <cstdarg>

#include "CefResourceReadCallback_N.h"
#include "jni_util.h"
#include "util.h"

namespace {

void DescribeAndClearException(JNIEnv* env) {
  if (!env || !env->ExceptionCheck())
    return;
  env->ExceptionDescribe();
  env->ExceptionClear();
}

bool IsValidJNIObject(JNIEnv* env, jobject object) {
  if (object && !env->ExceptionCheck())
    return true;
  DescribeAndClearException(env);
  return false;
}

bool ShouldRetainOpenCallback(bool call_succeeded, jboolean result, bool handle_request) {
  return call_succeeded && result != JNI_FALSE && !handle_request;
}

bool CallBooleanMethodChecked(JNIEnv* env, jobject object, const char* method, const char* signature, jboolean* result, ...) {
  if (result)
    *result = JNI_FALSE;
  if (!env || !object || !result)
    return false;

  ScopedJNIClass cls(env, env->GetObjectClass(object));
  jmethodID method_id = cls ? env->GetMethodID(cls, method, signature) : nullptr;
  if (!method_id || env->ExceptionCheck()) {
    DescribeAndClearException(env);
    return false;
  }

  va_list arguments;
  va_start(arguments, result);
  *result = env->CallBooleanMethodV(object, method_id, arguments);
  va_end(arguments);
  if (env->ExceptionCheck()) {
    DescribeAndClearException(env);
    return false;
  }
  return true;
}

bool CallVoidMethodChecked(JNIEnv* env, jobject object, const char* method, const char* signature, ...) {
  if (!env || !object)
    return false;

  ScopedJNIClass cls(env, env->GetObjectClass(object));
  jmethodID method_id = cls ? env->GetMethodID(cls, method, signature) : nullptr;
  if (!method_id || env->ExceptionCheck()) {
    DescribeAndClearException(env);
    return false;
  }

  va_list arguments;
  va_start(arguments, signature);
  env->CallVoidMethodV(object, method_id, arguments);
  va_end(arguments);
  if (env->ExceptionCheck()) {
    DescribeAndClearException(env);
    return false;
  }
  return true;
}

void FailResponseHeaders(CefRefPtr<CefResponse> response, int64_t& response_length, CefString& redirect_url) {
  if (response)
    response->SetError(ERR_FAILED);
  response_length = 0;
  redirect_url.clear();
}

}  // namespace

ResourceHandler::ResourceHandler(JNIEnv* env, jobject handler) : handle_(env, handler) {}

bool ResourceHandler::ProcessRequest(CefRefPtr<CefRequest> request, CefRefPtr<CefCallback> callback) {
  ScopedJNIEnv env;
  if (!env)
    return false;

  ScopedJNIRequest jrequest(env, request);
  ScopedJNICallback jcallback(env, callback);
  if (!IsValidJNIObject(env, jrequest.get()) ||
      !IsValidJNIObject(env, jcallback.get())) {
    if (jrequest)
      jrequest.SetTemporary();
    if (jcallback)
      jcallback.SetTemporary();
    return false;
  }
  jrequest.SetTemporary();
  jboolean jresult = JNI_FALSE;

  bool call_succeeded = CallBooleanMethodChecked(env, handle_, "processRequest", "(Lorg/cef/network/CefRequest;Lorg/cef/callback/CefCallback;)Z", &jresult, jrequest.get(), jcallback.get());

  if (!call_succeeded || jresult == JNI_FALSE) {
    // If the Java method returns "false" the callback won't be used and
    // the reference can therefore be removed.
    jcallback.SetTemporary();
  }

  return (jresult != JNI_FALSE);
}

bool ResourceHandler::Open(CefRefPtr<CefRequest> request, bool& handle_request, CefRefPtr<CefCallback> callback) {
  ScopedJNIEnv env;
  if (!env) {
    handle_request = true;
    return false;
  }

  ScopedJNIRequest jrequest(env, request);
  ScopedJNIBoolRef jhandleRequest(env, handle_request);
  ScopedJNICallback jcallback(env, callback);
  if (!IsValidJNIObject(env, jrequest.get()) ||
      !IsValidJNIObject(env, jhandleRequest.get()) ||
      !IsValidJNIObject(env, jcallback.get())) {
    if (jrequest)
      jrequest.SetTemporary();
    if (jcallback)
      jcallback.SetTemporary();
    handle_request = true;
    return false;
  }
  jrequest.SetTemporary();
  jboolean jresult = JNI_FALSE;

  bool call_succeeded = CallBooleanMethodChecked(env, handle_, "open", "(Lorg/cef/network/CefRequest;Lorg/cef/misc/BoolRef;Lorg/cef/callback/" "CefCallback;)Z", &jresult, jrequest.get(), jhandleRequest.get(), jcallback.get());

  handle_request = call_succeeded ? static_cast<bool>(jhandleRequest) : true;
  bool result = call_succeeded && jresult != JNI_FALSE;
  if (!ShouldRetainOpenCallback(call_succeeded, jresult, handle_request)) {
    // CEF consumes the callback only for a deferred decision (true/false).
    // Immediate handle/cancel and legacy fallback outcomes must release it now.
    jcallback.SetTemporary();
  }

  return result;
}

void ResourceHandler::GetResponseHeaders(CefRefPtr<CefResponse> response, int64_t& response_length, CefString& redirectUrl) {
  ScopedJNIEnv env;
  if (!env) {
    FailResponseHeaders(response, response_length, redirectUrl);
    return;
  }

  ScopedJNIResponse jresponse(env, response);
  ScopedJNILongRef jresponseLength(env, response_length);
  ScopedJNIStringRef jredirectUrl(env, redirectUrl);
  if (!IsValidJNIObject(env, jresponse.get()) ||
      !IsValidJNIObject(env, jresponseLength.get()) ||
      !IsValidJNIObject(env, jredirectUrl.get())) {
    if (jresponse)
      jresponse.SetTemporary();
    FailResponseHeaders(response, response_length, redirectUrl);
    return;
  }
  jresponse.SetTemporary();

  bool call_succeeded = CallVoidMethodChecked(env, handle_, "getResponseHeaders", "(Lorg/cef/network/CefResponse;Lorg/cef/misc/LongRef;Lorg/cef/misc/" "StringRef;)V", jresponse.get(), jresponseLength.get(), jredirectUrl.get());
  if (!call_succeeded) {
    FailResponseHeaders(response, response_length, redirectUrl);
    return;
  }

  response_length = jresponseLength;
  redirectUrl = jredirectUrl;
  if (response_length < -1)
    FailResponseHeaders(response, response_length, redirectUrl);
}

bool ResourceHandler::ReadResponse(void* data_out, int bytes_to_read, int& bytes_read, CefRefPtr<CefCallback> callback) {
  if (!data_out || bytes_to_read <= 0) {
    bytes_read = ERR_FAILED;
    return false;
  }
  ScopedJNIEnv env;
  if (!env) {
    bytes_read = ERR_FAILED;
    return false;
  }

  ScopedJNIIntRef jbytesRead(env, bytes_read);
  jbyteArray jbytes = env->NewByteArray(bytes_to_read);
  if (!jbytes) {
    if (env->ExceptionCheck())
      env->ExceptionClear();
    bytes_read = ERR_FAILED;
    return false;
  }
  ScopedJNICallback jcallback(env, callback);
  if (!IsValidJNIObject(env, jbytesRead.get()) ||
      !IsValidJNIObject(env, jcallback.get())) {
    if (jcallback)
      jcallback.SetTemporary();
    env->DeleteLocalRef(jbytes);
    bytes_read = ERR_FAILED;
    return false;
  }
  jboolean jresult = JNI_FALSE;

  bool call_succeeded = CallBooleanMethodChecked(env, handle_, "readResponse", "([BILorg/cef/misc/IntRef;Lorg/cef/callback/CefCallback;)Z", &jresult, jbytes, bytes_to_read, jbytesRead.get(), jcallback.get());

  if (!call_succeeded) {
    jcallback.SetTemporary();
    env->DeleteLocalRef(jbytes);
    bytes_read = ERR_FAILED;
    return false;
  }

  bytes_read = jbytesRead;

  bool result = (jresult != JNI_FALSE);
  if ((result && (bytes_read < 0 || bytes_read > bytes_to_read)) ||
      (!result && bytes_read > 0)) {
    LOG(ERROR) << "Invalid CefResourceHandler.readResponse result: returned "
               << result << " with bytes_read=" << bytes_read
               << " for capacity=" << bytes_to_read;
    result = false;
    bytes_read = ERR_FAILED;
  }
  if (!result || bytes_read > 0) {
    // The callback won't be used and the reference can therefore be removed.
    jcallback.SetTemporary();
  }

  if (result && bytes_read > 0) {
    jbyte* jbyte = env->GetByteArrayElements(jbytes, nullptr);
    if (jbyte) {
      memmove(data_out, jbyte, bytes_read);
      env->ReleaseByteArrayElements(jbytes, jbyte, JNI_ABORT);
    } else {
      if (env->ExceptionCheck())
        env->ExceptionClear();
      result = false;
      bytes_read = ERR_FAILED;
    }
  }
  env->DeleteLocalRef(jbytes);

  return result;
}

bool ResourceHandler::Read(void* data_out, int bytes_to_read, int& bytes_read, CefRefPtr<CefResourceReadCallback> callback) {
  if (!data_out || bytes_to_read <= 0) {
    bytes_read = ERR_FAILED;
    return false;
  }
  ScopedJNIEnv env;
  if (!env) {
    bytes_read = ERR_FAILED;
    return false;
  }

  ScopedJNIIntRef jbytesRead(env, bytes_read);
  jbyteArray jbytes = env->NewByteArray(bytes_to_read);
  if (!jbytes) {
    if (env->ExceptionCheck())
      env->ExceptionClear();
    bytes_read = ERR_FAILED;
    return false;
  }
  ScopedJNIResourceReadCallback jcallback(env, callback);
  if (!IsValidJNIObject(env, jbytesRead.get()) ||
      !IsValidJNIObject(env, jcallback.get())) {
    if (jcallback)
      jcallback.SetTemporary();
    env->DeleteLocalRef(jbytes);
    bytes_read = ERR_FAILED;
    return false;
  }

  // This callback must retain a reference to the data_out buffer. If setup
  // throws, invalidate the Java/native callback binding before returning.
  bool setup_succeeded = CallVoidMethodChecked(env, jcallback.get(), "setBufferRefs", "(J[B)V", reinterpret_cast<jlong>(data_out), jbytes);
  if (!setup_succeeded) {
    CallVoidMethodChecked(env, jcallback.get(), "clearBufferRefs", "()V");
    jcallback.SetTemporary();
    env->DeleteLocalRef(jbytes);
    bytes_read = ERR_FAILED;
    return false;
  }

  jboolean jresult = JNI_FALSE;

  bool call_succeeded = CallBooleanMethodChecked(env, handle_, "read", "([BILorg/cef/misc/IntRef;Lorg/cef/callback/CefResourceReadCallback;)Z", &jresult, jbytes, bytes_to_read, jbytesRead.get(), jcallback.get());

  if (!call_succeeded) {
    CallVoidMethodChecked(env, jcallback.get(), "clearBufferRefs", "()V");
    jcallback.SetTemporary();
    env->DeleteLocalRef(jbytes);
    bytes_read = ERR_FAILED;
    return false;
  }

  bytes_read = jbytesRead;

  bool result = (jresult != JNI_FALSE);
  if ((result && (bytes_read < 0 || bytes_read > bytes_to_read)) ||
      (!result && bytes_read > 0)) {
    LOG(ERROR) << "Invalid CefResourceHandler.read result: returned " << result
               << " with bytes_read=" << bytes_read
               << " for capacity=" << bytes_to_read;
    result = false;
    bytes_read = ERR_FAILED;
  }
  if (!result || bytes_read > 0) {
    // The callback won't be used and the reference can therefore be removed.
    CallVoidMethodChecked(env, jcallback.get(), "clearBufferRefs", "()V");
    jcallback.SetTemporary();
  }

  if (result && bytes_read > 0) {
    jbyte* jbyte = env->GetByteArrayElements(jbytes, nullptr);
    if (jbyte) {
      memmove(data_out, jbyte, bytes_read);
      env->ReleaseByteArrayElements(jbytes, jbyte, JNI_ABORT);
    } else {
      if (env->ExceptionCheck())
        env->ExceptionClear();
      result = false;
      bytes_read = ERR_FAILED;
    }
  }
  env->DeleteLocalRef(jbytes);

  return result;
}

bool ResourceHandler::Skip(int64_t bytes_to_skip, int64_t& bytes_skipped, CefRefPtr<CefResourceSkipCallback> callback) {
  if (bytes_to_skip <= 0) {
    bytes_skipped = ERR_FAILED;
    return false;
  }
  ScopedJNIEnv env;
  if (!env) {
    bytes_skipped = ERR_FAILED;
    return false;
  }

  ScopedJNILongRef jbytesSkipped(env, bytes_skipped);
  ScopedJNIResourceSkipCallback jcallback(env, callback);
  if (!IsValidJNIObject(env, jbytesSkipped.get()) ||
      !IsValidJNIObject(env, jcallback.get())) {
    if (jcallback)
      jcallback.SetTemporary();
    bytes_skipped = ERR_FAILED;
    return false;
  }
  bool setup_succeeded = CallVoidMethodChecked(env, jcallback.get(), "setBytesToSkip", "(J)V", static_cast<jlong>(bytes_to_skip));
  if (!setup_succeeded) {
    CallVoidMethodChecked(env, jcallback.get(), "clearPending", "()V");
    jcallback.SetTemporary();
    bytes_skipped = ERR_FAILED;
    return false;
  }
  jboolean jresult = JNI_FALSE;

  bool call_succeeded = CallBooleanMethodChecked(env, handle_, "skip", "(JLorg/cef/misc/LongRef;Lorg/cef/callback/CefResourceSkipCallback;)Z", &jresult, static_cast<jlong>(bytes_to_skip), jbytesSkipped.get(), jcallback.get());

  if (!call_succeeded) {
    CallVoidMethodChecked(env, jcallback.get(), "clearPending", "()V");
    jcallback.SetTemporary();
    bytes_skipped = ERR_FAILED;
    return false;
  }

  bytes_skipped = jbytesSkipped;

  bool result = (jresult != JNI_FALSE);
  if ((result && (bytes_skipped < 0 || bytes_skipped > bytes_to_skip)) ||
      (!result && bytes_skipped > 0)) {
    LOG(ERROR) << "Invalid CefResourceHandler.skip result: returned " << result
               << " with bytes_skipped=" << bytes_skipped
               << " for requested=" << bytes_to_skip;
    result = false;
    bytes_skipped = ERR_FAILED;
  }
  if (!result || bytes_skipped > 0) {
    // The callback won't be used and the reference can therefore be removed.
    CallVoidMethodChecked(env, jcallback.get(), "clearPending", "()V");
    jcallback.SetTemporary();
  }

  return result;
}

JNIEXPORT jboolean JNICALL Java_org_cef_callback_CefResourceReadCallback_1N_N_1TestSetupCallForTesting(JNIEnv* env, jclass, jobject target) {
  jbyteArray buffer = env->NewByteArray(1);
  if (!buffer) {
    DescribeAndClearException(env);
    return JNI_FALSE;
  }
  bool result = CallVoidMethodChecked(env, target, "setBufferRefs", "(J[B)V", static_cast<jlong>(0), buffer);
  env->DeleteLocalRef(buffer);
  return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_org_cef_callback_CefResourceReadCallback_1N_N_1TestOpenCallbackRetentionForTesting(JNIEnv*, jclass, jboolean call_succeeded, jboolean result, jboolean handle_request) {
  return ShouldRetainOpenCallback(call_succeeded != JNI_FALSE, result, handle_request != JNI_FALSE)
             ? JNI_TRUE
             : JNI_FALSE;
}

void ResourceHandler::Cancel() {
  ScopedJNIEnv env;
  if (!env)
    return;
  JNI_CALL_VOID_METHOD(env, handle_, "cancel", "()V");
}
