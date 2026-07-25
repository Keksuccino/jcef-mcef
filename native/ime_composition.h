// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#ifndef JCEF_NATIVE_IME_COMPOSITION_H_
#define JCEF_NATIVE_IME_COMPOSITION_H_
#pragma once

#include <jni.h>

#include <vector>

#include "include/cef_browser.h"

namespace ime_composition {

// Owns every value that CEF may copy into a posted UI-thread task. Conversion
// commits this object only after all Java objects have been checked, preventing
// partial native IME updates.
struct SetComposition {
  CefString text;
  std::vector<CefCompositionUnderline> underlines;
  CefRange replacement_range;
  CefRange selection_range;
};

struct CommitText {
  CefString text;
  CefRange replacement_range;
};

bool ConvertSetComposition(JNIEnv* env, jstring text, jobjectArray underlines, jobject replacement_range, jobject selection_range, SetComposition* output);
bool ConvertCommitText(JNIEnv* env, jstring text, jobject replacement_range, CommitText* output);

// Returns {String, long[]} for native conversion regression tests. The long
// array contains the two ranges, native structure size, underline count, and
// every converted underline field.
jobjectArray NewSnapshot(JNIEnv* env, jstring text, jobjectArray underlines, jobject replacement_range, jobject selection_range);

}  // namespace ime_composition

#endif  // JCEF_NATIVE_IME_COMPOSITION_H_
