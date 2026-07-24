// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "browser_settings.h"

#include <bit>
#include <cstdint>
#include <string>

#include "jni_scoped_helpers.h"
#include "jni_util.h"

namespace browser_settings {
namespace {

static_assert(STATE_DEFAULT == 0 && STATE_ENABLED == 1 && STATE_DISABLED == 2, "CefState Java values must match cef_state_t");

bool Reject(JNIEnv* env, const std::string& message, std::string* error) {
  if (error)
    *error = message;
  // The caller decides whether semantic failures should become Java
  // exceptions. Naturally pending VM exceptions must remain untouched.
  (void)env;
  return false;
}

bool ReadInt(JNIEnv* env, jclass cls, jobject object, const char* name, int* value, std::string* error) {
  jfieldID field = env->GetFieldID(cls, name, "I");
  if (!field)
    return Reject(env, std::string("CefBrowserSettings.") + name + " is unavailable", error);
  const jint result = env->GetIntField(object, field);
  if (env->ExceptionCheck())
    return Reject(env, std::string("Failed to read CefBrowserSettings.") + name, error);
  *value = result;
  return true;
}

bool ReadString(JNIEnv* env, jclass cls, jobject object, const char* name, cef_string_t* value, std::string* error) {
  jfieldID field = env->GetFieldID(cls, name, "Ljava/lang/String;");
  if (!field)
    return Reject(env, std::string("CefBrowserSettings.") + name + " is unavailable", error);
  ScopedJNIObjectLocal result(env, env->GetObjectField(object, field));
  if (env->ExceptionCheck())
    return Reject(env, std::string("Failed to read CefBrowserSettings.") + name, error);
  if (!result)
    return true;
  const CefString string_value = GetJNIString(env, static_cast<jstring>(result.get()));
  if (env->ExceptionCheck())
    return Reject(env, std::string("Failed to convert CefBrowserSettings.") + name, error);
  if (!string_value.empty()) {
    CefString target(value);
    target = string_value;
  }
  return true;
}

bool ReadState(JNIEnv* env, jclass cls, jobject object, const char* name, cef_state_t* value, std::string* error) {
  jfieldID field = env->GetFieldID(cls, name, "Lorg/cef/CefState;");
  if (!field)
    return Reject(env, std::string("CefBrowserSettings.") + name + " is unavailable", error);
  ScopedJNIObjectLocal state(env, env->GetObjectField(object, field));
  if (env->ExceptionCheck())
    return Reject(env, std::string("Failed to read CefBrowserSettings.") + name, error);
  if (!state)
    return Reject(env, std::string("CefBrowserSettings.") + name + " must not be null", error);
  ScopedJNIClass state_class(env, env->GetObjectClass(state));
  if (!state_class)
    return Reject(env, std::string("Failed to inspect CefBrowserSettings.") + name, error);
  jmethodID get_value = env->GetMethodID(state_class, "getValue", "()I");
  if (!get_value)
    return Reject(env, std::string("CefBrowserSettings.") + name + " has no CefState.getValue()", error);
  const jint state_value = env->CallIntMethod(state, get_value);
  if (env->ExceptionCheck())
    return Reject(env, std::string("Failed to read CefState value for CefBrowserSettings.") + name, error);
  if (state_value < STATE_DEFAULT || state_value > STATE_DISABLED)
    return Reject(env, std::string("CefBrowserSettings.") + name + " has invalid CefState value " + std::to_string(state_value), error);
  *value = static_cast<cef_state_t>(state_value);
  return true;
}

bool ReadColor(JNIEnv* env, jclass cls, jobject object, bool osr, bool transparent, cef_color_t* value, std::string* error) {
  jfieldID field = env->GetFieldID(cls, "background_color", "Lorg/cef/CefColor;");
  if (!field)
    return Reject(env, "CefBrowserSettings.background_color is unavailable", error);
  ScopedJNIObjectLocal color(env, env->GetObjectField(object, field));
  if (env->ExceptionCheck())
    return Reject(env, "Failed to read CefBrowserSettings.background_color", error);
  if (!color)
    return true;
  ScopedJNIClass color_class(env, env->GetObjectClass(color));
  if (!color_class)
    return Reject(env, "Failed to inspect CefBrowserSettings.background_color", error);
  jmethodID get_argb = env->GetMethodID(color_class, "getArgb", "()I");
  if (!get_argb)
    return Reject(env, "CefBrowserSettings.background_color has no CefColor.getArgb()", error);
  const jint signed_argb = env->CallIntMethod(color, get_argb);
  if (env->ExceptionCheck())
    return Reject(env, "Failed to read CefBrowserSettings.background_color ARGB value", error);
  const uint32_t argb = static_cast<uint32_t>(signed_argb);
  const uint32_t alpha = argb >> 24;
  if (alpha != 0 && alpha != 255)
    return Reject(env, "CefBrowserSettings.background_color alpha must be 0 or 255", error);
  if (osr && (alpha == 0) != transparent)
    return Reject(env, "CefBrowserSettings.background_color alpha must match browser transparency", error);
  *value = static_cast<cef_color_t>(argb);
  return true;
}

bool PutObject(JNIEnv* env, jobject map, jmethodID put, const char* name, jobject value) {
  ScopedJNIString key(env, name);
  if (!key || !value)
    return false;
  ScopedJNIObjectLocal previous(env, env->CallObjectMethod(map, put, key.get(), value));
  return !env->ExceptionCheck();
}

bool PutInt(JNIEnv* env, jobject map, jmethodID put, const char* name, int value) {
  ScopedJNIObjectLocal boxed(env, NewJNIInteger(env, value));
  return PutObject(env, map, put, name, boxed.get());
}

bool PutString(JNIEnv* env, jobject map, jmethodID put, const char* name, const cef_string_t* value) {
  ScopedJNIString string(env, CefString(value));
  return PutObject(env, map, put, name, string.get());
}

}  // namespace

bool Convert(JNIEnv* env, jobject jsettings, bool osr, bool transparent, CefBrowserSettings* output, std::string* error) {
  if (!output)
    return Reject(env, "Native CefBrowserSettings output must not be null", error);

  CefBrowserSettings converted;
  // MCEF's existing transparency flag remains authoritative for off-screen
  // rendering. A stray windowed transparency flag must not change the legacy
  // opaque-white default.
  converted.background_color = osr && transparent ? 0 : CefColorSetARGB(255, 255, 255, 255);
  if (!jsettings) {
    *output = converted;
    return true;
  }

  ScopedJNIClass cls(env, env->GetObjectClass(jsettings));
  if (!cls)
    return Reject(env, "Failed to inspect CefBrowserSettings", error);

  if (!ReadInt(env, cls, jsettings, "windowless_frame_rate", &converted.windowless_frame_rate, error))
    return false;
  if (converted.windowless_frame_rate < 0)
    return Reject(env, "CefBrowserSettings.windowless_frame_rate must be 0 or greater", error);

  if (!ReadString(env, cls, jsettings, "standard_font_family", &converted.standard_font_family, error) ||
      !ReadString(env, cls, jsettings, "fixed_font_family", &converted.fixed_font_family, error) ||
      !ReadString(env, cls, jsettings, "serif_font_family", &converted.serif_font_family, error) ||
      !ReadString(env, cls, jsettings, "sans_serif_font_family", &converted.sans_serif_font_family, error) ||
      !ReadString(env, cls, jsettings, "cursive_font_family", &converted.cursive_font_family, error) ||
      !ReadString(env, cls, jsettings, "fantasy_font_family", &converted.fantasy_font_family, error))
    return false;

  if (!ReadInt(env, cls, jsettings, "default_font_size", &converted.default_font_size, error) ||
      !ReadInt(env, cls, jsettings, "default_fixed_font_size", &converted.default_fixed_font_size, error) ||
      !ReadInt(env, cls, jsettings, "minimum_font_size", &converted.minimum_font_size, error) ||
      !ReadInt(env, cls, jsettings, "minimum_logical_font_size", &converted.minimum_logical_font_size, error))
    return false;

  if (!ReadString(env, cls, jsettings, "default_encoding", &converted.default_encoding, error))
    return false;

  if (!ReadState(env, cls, jsettings, "remote_fonts", &converted.remote_fonts, error) ||
      !ReadState(env, cls, jsettings, "javascript", &converted.javascript, error) ||
      !ReadState(env, cls, jsettings, "javascript_close_windows", &converted.javascript_close_windows, error) ||
      !ReadState(env, cls, jsettings, "javascript_access_clipboard", &converted.javascript_access_clipboard, error) ||
      !ReadState(env, cls, jsettings, "javascript_dom_paste", &converted.javascript_dom_paste, error) ||
      !ReadState(env, cls, jsettings, "image_loading", &converted.image_loading, error) ||
      !ReadState(env, cls, jsettings, "image_shrink_standalone_to_fit", &converted.image_shrink_standalone_to_fit, error) ||
      !ReadState(env, cls, jsettings, "text_area_resize", &converted.text_area_resize, error) ||
      !ReadState(env, cls, jsettings, "tab_to_links", &converted.tab_to_links, error) ||
      !ReadState(env, cls, jsettings, "local_storage", &converted.local_storage, error) ||
      !ReadState(env, cls, jsettings, "webgl", &converted.webgl, error) ||
      !ReadState(env, cls, jsettings, "chrome_status_bubble", &converted.chrome_status_bubble, error) ||
      !ReadState(env, cls, jsettings, "chrome_zoom_bubble", &converted.chrome_zoom_bubble, error))
    return false;

  // databases_deprecated is retained in the CEF ABI but intentionally ignored
  // by CEF 151's renderer preferences and its C++ wrapper copy traits.
  if (!ReadColor(env, cls, jsettings, osr, transparent, &converted.background_color, error))
    return false;

  // Commit only after every JNI read and validation succeeds so callers never
  // observe a partially converted settings structure.
  *output = converted;
  return true;
}

jobject NewSnapshot(JNIEnv* env, const CefBrowserSettings& settings) {
  ScopedJNIObjectLocal map(env, NewJNIHashMap(env));
  if (!map)
    return nullptr;
  ScopedJNIClass map_class(env, env->GetObjectClass(map));
  if (!map_class)
    return nullptr;
  jmethodID put = env->GetMethodID(map_class, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
  if (!put)
    return nullptr;

  if (!PutInt(env, map, put, "windowless_frame_rate", settings.windowless_frame_rate) ||
      !PutString(env, map, put, "standard_font_family", &settings.standard_font_family) ||
      !PutString(env, map, put, "fixed_font_family", &settings.fixed_font_family) ||
      !PutString(env, map, put, "serif_font_family", &settings.serif_font_family) ||
      !PutString(env, map, put, "sans_serif_font_family", &settings.sans_serif_font_family) ||
      !PutString(env, map, put, "cursive_font_family", &settings.cursive_font_family) ||
      !PutString(env, map, put, "fantasy_font_family", &settings.fantasy_font_family) ||
      !PutInt(env, map, put, "default_font_size", settings.default_font_size) ||
      !PutInt(env, map, put, "default_fixed_font_size", settings.default_fixed_font_size) ||
      !PutInt(env, map, put, "minimum_font_size", settings.minimum_font_size) ||
      !PutInt(env, map, put, "minimum_logical_font_size", settings.minimum_logical_font_size) ||
      !PutString(env, map, put, "default_encoding", &settings.default_encoding) ||
      !PutInt(env, map, put, "remote_fonts", settings.remote_fonts) ||
      !PutInt(env, map, put, "javascript", settings.javascript) ||
      !PutInt(env, map, put, "javascript_close_windows", settings.javascript_close_windows) ||
      !PutInt(env, map, put, "javascript_access_clipboard", settings.javascript_access_clipboard) ||
      !PutInt(env, map, put, "javascript_dom_paste", settings.javascript_dom_paste) ||
      !PutInt(env, map, put, "image_loading", settings.image_loading) ||
      !PutInt(env, map, put, "image_shrink_standalone_to_fit", settings.image_shrink_standalone_to_fit) ||
      !PutInt(env, map, put, "text_area_resize", settings.text_area_resize) ||
      !PutInt(env, map, put, "tab_to_links", settings.tab_to_links) ||
      !PutInt(env, map, put, "local_storage", settings.local_storage) ||
      !PutInt(env, map, put, "webgl", settings.webgl) ||
      !PutInt(env, map, put, "background_color", std::bit_cast<int32_t>(static_cast<uint32_t>(settings.background_color))) ||
      !PutInt(env, map, put, "chrome_status_bubble", settings.chrome_status_bubble) ||
      !PutInt(env, map, put, "chrome_zoom_bubble", settings.chrome_zoom_bubble))
    return nullptr;

  return map.Release();
}

}  // namespace browser_settings
