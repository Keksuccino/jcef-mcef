// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "resolve_callback.h"

#include "jni_util.h"

ResolveCallback::ResolveCallback(JNIEnv* env, jobject callback) : handle_(env, callback) {}

void ResolveCallback::OnResolveCompleted(cef_errorcode_t result, const std::vector<CefString>& resolved_ips) {
  if (completed_.exchange(true, std::memory_order_acq_rel))
    return;

  ScopedJNIEnv env;
  if (!env)
    return;

  ScopedJNIObjectLocal addresses(env, NewJNIStringVector(env, resolved_ips));
  if (!addresses)
    return;
  ScopedJNIObjectLocal resolve_result(env, NewJNIObject(env, "org/cef/callback/CefResolveResult", "(ILjava/util/List;)V", static_cast<jint>(result), addresses.get()));
  if (!resolve_result)
    return;

  JNI_CALL_VOID_METHOD(env, handle_, "onResolveCompleted", "(Lorg/cef/callback/CefResolveResult;)V", resolve_result.get());
}
