// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#ifndef JCEF_NATIVE_DOUBLE_CALLBACK_H_
#define JCEF_NATIVE_DOUBLE_CALLBACK_H_
#pragma once

#include <jni.h>

#include "jni_scoped_helpers.h"

// Callback for returning a double query result with an explicit success state.
// Scheduling failures may complete on the JNI caller while successful queries
// complete on the browser process UI thread.
class DoubleCallback : public virtual CefBaseRefCounted {
 public:
  DoubleCallback(JNIEnv* env, jobject jcallback);

  virtual void onComplete(bool success, double value);

 protected:
  ScopedJNIObjectGlobal handle_;

  // Include the default reference counting implementation.
  IMPLEMENT_REFCOUNTING(DoubleCallback);
};

#endif  // JCEF_NATIVE_DOUBLE_CALLBACK_H_
