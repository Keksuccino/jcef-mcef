// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#ifndef JCEF_NATIVE_BROWSER_SETTINGS_H_
#define JCEF_NATIVE_BROWSER_SETTINGS_H_

#include <jni.h>

#include <string>

#include "include/cef_browser.h"

namespace browser_settings {

// Converts the Java browser settings as one all-or-nothing operation. On
// failure |error| contains a stable diagnostic and any JVM-originated
// exception remains pending for the caller to handle.
bool Convert(JNIEnv* env, jobject jsettings, bool osr, bool transparent, CefBrowserSettings* output, std::string* error);

// Returns a deterministic Java Map<String, Object> view of every setting that
// Convert maps. The deprecated databases ABI slot is intentionally absent
// because CEF 151 does not consume it.
jobject NewSnapshot(JNIEnv* env, const CefBrowserSettings& settings);

}  // namespace browser_settings

#endif  // JCEF_NATIVE_BROWSER_SETTINGS_H_
