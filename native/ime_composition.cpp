// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "ime_composition.h"

#include <cstddef>
#include <cstdint>
#include <limits>
#include <string>
#include <utility>
#include <vector>

#include "jni_scoped_helpers.h"
#include "jni_util.h"

namespace ime_composition {
namespace {

static_assert(CEF_API_VERSION == 15100, "CEF API changed: re-audit the IME underline field mapping and Java bridge contract");
static_assert(CEF_CUS_SOLID == 0 && CEF_CUS_DOT == 1 && CEF_CUS_DASH == 2 && CEF_CUS_NONE == 3 && CEF_CUS_NUM_VALUES == 4, "CefCompositionUnderlineStyle Java values must match CEF API 15100");
static_assert(sizeof(cef_color_t) == sizeof(jint), "Java and CEF packed colors must have the same width");
static_assert(sizeof(cef_range_t) == 2 * sizeof(uint32_t), "CEF range ABI changed");
static_assert(offsetof(cef_composition_underline_t, size) == 0, "CEF composition underline size must remain the leading ABI field");

constexpr uint64_t kMaxCefRangeEndpoint = std::numeric_limits<uint32_t>::max();
constexpr size_t kSnapshotHeaderFields = 6;
constexpr size_t kSnapshotUnderlineFields = 7;

bool Reject(JNIEnv* env, const char* exception_class, const std::string& message) {
  if (!env->ExceptionCheck()) {
    ScopedJNIClass cls(env, exception_class);
    if (cls)
      env->ThrowNew(cls, message.c_str());
  }
  return false;
}

class Converter {
 public:
  explicit Converter(JNIEnv* env) : env_(env), range_class_(env, "org/cef/misc/CefRange"), underline_class_(env, "org/cef/input/CefCompositionUnderline"), color_class_(env, "org/cef/CefColor"), style_class_(env, "org/cef/input/CefCompositionUnderlineStyle") {
    if (!range_class_ || !underline_class_ || !color_class_ || !style_class_)
      return;
    range_get_from_ = GetMethod(range_class_, "getFrom", "()J");
    range_get_to_ = GetMethod(range_class_, "getTo", "()J");
    underline_get_range_ = GetMethod(underline_class_, "getRange", "()Lorg/cef/misc/CefRange;");
    underline_get_color_ = GetMethod(underline_class_, "getColor", "()Lorg/cef/CefColor;");
    underline_get_background_color_ = GetMethod(underline_class_, "getBackgroundColor", "()Lorg/cef/CefColor;");
    underline_is_thick_ = GetMethod(underline_class_, "isThick", "()Z");
    underline_get_style_ = GetMethod(underline_class_, "getStyle", "()Lorg/cef/input/CefCompositionUnderlineStyle;");
    color_get_argb_ = GetMethod(color_class_, "getArgb", "()I");
    style_get_value_ = GetMethod(style_class_, "getValue", "()I");
  }

  bool valid() const { return range_class_ && underline_class_ && color_class_ && style_class_ && range_get_from_ && range_get_to_ && underline_get_range_ && underline_get_color_ && underline_get_background_color_ && underline_is_thick_ && underline_get_style_ && color_get_argb_ && style_get_value_ && !env_->ExceptionCheck(); }

  bool ReadRange(jobject value, const std::string& name, CefRange* output) const {
    if (!value)
      return Reject(env_, "java/lang/NullPointerException", name + " must not be null");
    if (env_->IsInstanceOf(value, range_class_) != JNI_TRUE)
      return Reject(env_, "java/lang/IllegalArgumentException", name + " is not a CefRange");

    const jlong from = env_->CallLongMethod(value, range_get_from_);
    if (env_->ExceptionCheck())
      return false;
    const jlong to = env_->CallLongMethod(value, range_get_to_);
    if (env_->ExceptionCheck())
      return false;
    if (from < 0 || static_cast<uint64_t>(from) > kMaxCefRangeEndpoint || to < 0 || static_cast<uint64_t>(to) > kMaxCefRangeEndpoint)
      return Reject(env_, "java/lang/IllegalArgumentException", name + " endpoints must fit CEF's unsigned 32-bit range");

    *output = CefRange(static_cast<uint32_t>(from), static_cast<uint32_t>(to));
    return true;
  }

  bool ReadUnderlines(jobjectArray values, uint32_t text_length, std::vector<CefCompositionUnderline>* output) const {
    if (!values)
      return Reject(env_, "java/lang/NullPointerException", "underlines must not be null");
    const jsize count = env_->GetArrayLength(values);
    if (env_->ExceptionCheck())
      return false;

    std::vector<CefCompositionUnderline> converted;
    converted.reserve(static_cast<size_t>(count));
    for (jsize index = 0; index < count; ++index) {
      const std::string name = "underlines[" + std::to_string(index) + "]";
      ScopedJNIObjectLocal underline(env_, env_->GetObjectArrayElement(values, index));
      if (env_->ExceptionCheck())
        return false;
      if (!underline)
        return Reject(env_, "java/lang/NullPointerException", name + " must not be null");
      if (env_->IsInstanceOf(underline.get(), underline_class_) != JNI_TRUE)
        return Reject(env_, "java/lang/IllegalArgumentException", name + " is not a CefCompositionUnderline");

      ScopedJNIObjectLocal range(env_, env_->CallObjectMethod(underline.get(), underline_get_range_));
      if (env_->ExceptionCheck())
        return false;
      ScopedJNIObjectLocal color(env_, env_->CallObjectMethod(underline.get(), underline_get_color_));
      if (env_->ExceptionCheck())
        return false;
      ScopedJNIObjectLocal background_color(env_, env_->CallObjectMethod(underline.get(), underline_get_background_color_));
      if (env_->ExceptionCheck())
        return false;
      const jboolean thick = env_->CallBooleanMethod(underline.get(), underline_is_thick_);
      if (env_->ExceptionCheck())
        return false;
      ScopedJNIObjectLocal style(env_, env_->CallObjectMethod(underline.get(), underline_get_style_));
      if (env_->ExceptionCheck())
        return false;

      CefRange cef_range;
      if (!ReadRange(range.get(), name + ".range", &cef_range))
        return false;
      if (cef_range == CefRange::InvalidRange())
        return Reject(env_, "java/lang/IllegalArgumentException", name + ".range must be valid");
      if (cef_range.from > cef_range.to)
        return Reject(env_, "java/lang/IllegalArgumentException", name + ".range must be forward");
      if (cef_range.from > text_length || cef_range.to > text_length)
        return Reject(env_, "java/lang/IllegalArgumentException", name + ".range exceeds the composition text's UTF-16 length");

      cef_color_t cef_color = 0;
      cef_color_t cef_background_color = 0;
      cef_composition_underline_style_t cef_style = CEF_CUS_SOLID;
      if (!ReadColor(color.get(), name + ".color", &cef_color) || !ReadColor(background_color.get(), name + ".backgroundColor", &cef_background_color) || !ReadStyle(style.get(), name + ".style", &cef_style))
        return false;

      // CefStructBaseSimple initializes both the leading ABI size and all
      // fields that this bridge does not explicitly assign. Raw aggregate
      // zero-initialization would incorrectly leave size at zero and make later
      // CEF versions ignore the structure.
      CefCompositionUnderline converted_underline;
      converted_underline.range = cef_range;
      converted_underline.color = cef_color;
      converted_underline.background_color = cef_background_color;
      converted_underline.thick = thick != JNI_FALSE;
      converted_underline.style = cef_style;
      converted.push_back(converted_underline);
    }

    *output = std::move(converted);
    return true;
  }

 private:
  jmethodID GetMethod(jclass cls, const char* name, const char* signature) const {
    if (env_->ExceptionCheck())
      return nullptr;
    return env_->GetMethodID(cls, name, signature);
  }

  bool ReadColor(jobject value, const std::string& name, cef_color_t* output) const {
    if (!value)
      return Reject(env_, "java/lang/NullPointerException", name + " must not be null");
    if (env_->IsInstanceOf(value, color_class_) != JNI_TRUE)
      return Reject(env_, "java/lang/IllegalArgumentException", name + " is not a CefColor");
    const jint argb = env_->CallIntMethod(value, color_get_argb_);
    if (env_->ExceptionCheck())
      return false;
    *output = static_cast<cef_color_t>(static_cast<uint32_t>(argb));
    return true;
  }

  bool ReadStyle(jobject value, const std::string& name, cef_composition_underline_style_t* output) const {
    if (!value)
      return Reject(env_, "java/lang/NullPointerException", name + " must not be null");
    if (env_->IsInstanceOf(value, style_class_) != JNI_TRUE)
      return Reject(env_, "java/lang/IllegalArgumentException", name + " is not a CefCompositionUnderlineStyle");
    const jint style = env_->CallIntMethod(value, style_get_value_);
    if (env_->ExceptionCheck())
      return false;
    if (style < CEF_CUS_SOLID || style >= CEF_CUS_NUM_VALUES)
      return Reject(env_, "java/lang/IllegalArgumentException", name + " is outside the CEF API 15100 range");
    *output = static_cast<cef_composition_underline_style_t>(style);
    return true;
  }

  JNIEnv* const env_;
  ScopedJNIClass range_class_;
  ScopedJNIClass underline_class_;
  ScopedJNIClass color_class_;
  ScopedJNIClass style_class_;
  jmethodID range_get_from_ = nullptr;
  jmethodID range_get_to_ = nullptr;
  jmethodID underline_get_range_ = nullptr;
  jmethodID underline_get_color_ = nullptr;
  jmethodID underline_get_background_color_ = nullptr;
  jmethodID underline_is_thick_ = nullptr;
  jmethodID underline_get_style_ = nullptr;
  jmethodID color_get_argb_ = nullptr;
  jmethodID style_get_value_ = nullptr;
};

bool RequireSetArguments(JNIEnv* env, jstring text, jobjectArray underlines, jobject replacement_range, jobject selection_range, SetComposition* output) {
  if (!text)
    return Reject(env, "java/lang/NullPointerException", "text must not be null");
  if (!underlines)
    return Reject(env, "java/lang/NullPointerException", "underlines must not be null");
  if (!replacement_range)
    return Reject(env, "java/lang/NullPointerException", "replacementRange must not be null");
  if (!selection_range)
    return Reject(env, "java/lang/NullPointerException", "selectionRange must not be null");
  if (!output)
    return Reject(env, "java/lang/IllegalStateException", "Native SetComposition output must not be null");
  return true;
}

}  // namespace

bool ConvertSetComposition(JNIEnv* env, jstring text, jobjectArray underlines, jobject replacement_range, jobject selection_range, SetComposition* output) {
  if (!RequireSetArguments(env, text, underlines, replacement_range, selection_range, output))
    return false;

  const jsize text_length = env->GetStringLength(text);
  if (env->ExceptionCheck())
    return false;
  Converter converter(env);
  if (!converter.valid())
    return false;

  SetComposition converted;
  converted.text = GetJNIString(env, text);
  if (env->ExceptionCheck() || !converter.ReadUnderlines(underlines, static_cast<uint32_t>(text_length), &converted.underlines) || !converter.ReadRange(replacement_range, "replacementRange", &converted.replacement_range) || !converter.ReadRange(selection_range, "selectionRange", &converted.selection_range))
    return false;

  *output = std::move(converted);
  return true;
}

bool ConvertCommitText(JNIEnv* env, jstring text, jobject replacement_range, CommitText* output) {
  if (!text)
    return Reject(env, "java/lang/NullPointerException", "text must not be null");
  if (!replacement_range)
    return Reject(env, "java/lang/NullPointerException", "replacementRange must not be null");
  if (!output)
    return Reject(env, "java/lang/IllegalStateException", "Native CommitText output must not be null");

  Converter converter(env);
  if (!converter.valid())
    return false;
  CommitText converted;
  converted.text = GetJNIString(env, text);
  if (env->ExceptionCheck() || !converter.ReadRange(replacement_range, "replacementRange", &converted.replacement_range))
    return false;

  *output = std::move(converted);
  return true;
}

jobjectArray NewSnapshot(JNIEnv* env, jstring text, jobjectArray underlines, jobject replacement_range, jobject selection_range) {
  SetComposition converted;
  if (!ConvertSetComposition(env, text, underlines, replacement_range, selection_range, &converted))
    return nullptr;

  const size_t underline_count = converted.underlines.size();
  if (underline_count > (static_cast<size_t>(std::numeric_limits<jsize>::max()) - kSnapshotHeaderFields) / kSnapshotUnderlineFields) {
    Reject(env, "java/lang/OutOfMemoryError", "IME conversion snapshot exceeds Java array capacity");
    return nullptr;
  }
  std::vector<jlong> values(kSnapshotHeaderFields + underline_count * kSnapshotUnderlineFields);
  values[0] = static_cast<jlong>(converted.replacement_range.from);
  values[1] = static_cast<jlong>(converted.replacement_range.to);
  values[2] = static_cast<jlong>(converted.selection_range.from);
  values[3] = static_cast<jlong>(converted.selection_range.to);
  values[4] = static_cast<jlong>(sizeof(cef_composition_underline_t));
  values[5] = static_cast<jlong>(underline_count);
  for (size_t index = 0; index < underline_count; ++index) {
    const CefCompositionUnderline& underline = converted.underlines[index];
    const size_t offset = kSnapshotHeaderFields + index * kSnapshotUnderlineFields;
    values[offset] = static_cast<jlong>(underline.size);
    values[offset + 1] = static_cast<jlong>(underline.range.from);
    values[offset + 2] = static_cast<jlong>(underline.range.to);
    values[offset + 3] = static_cast<jlong>(underline.color);
    values[offset + 4] = static_cast<jlong>(underline.background_color);
    values[offset + 5] = underline.thick ? 1 : 0;
    values[offset + 6] = static_cast<jlong>(underline.style);
  }

  ScopedJNIClass object_class(env, "java/lang/Object");
  if (!object_class)
    return nullptr;
  jobjectArray snapshot = env->NewObjectArray(2, object_class, nullptr);
  if (!snapshot)
    return nullptr;
  ScopedJNIString converted_text(env, converted.text);
  if (!converted_text || env->ExceptionCheck()) {
    env->DeleteLocalRef(snapshot);
    return nullptr;
  }
  ScopedJNIObjectLocal converted_values(env, env->NewLongArray(static_cast<jsize>(values.size())));
  if (!converted_values || env->ExceptionCheck()) {
    env->DeleteLocalRef(snapshot);
    return nullptr;
  }
  env->SetLongArrayRegion(static_cast<jlongArray>(converted_values.get()), 0, static_cast<jsize>(values.size()), values.data());
  if (env->ExceptionCheck()) {
    env->DeleteLocalRef(snapshot);
    return nullptr;
  }
  env->SetObjectArrayElement(snapshot, 0, converted_text.get());
  if (env->ExceptionCheck()) {
    env->DeleteLocalRef(snapshot);
    return nullptr;
  }
  env->SetObjectArrayElement(snapshot, 1, converted_values.get());
  if (env->ExceptionCheck()) {
    env->DeleteLocalRef(snapshot);
    return nullptr;
  }
  return snapshot;
}

}  // namespace ime_composition
