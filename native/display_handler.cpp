// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "display_handler.h"

#include <cmath>
#include <cstdint>
#include <limits>

#include "include/base/cef_logging.h"
#include "jni_util.h"

namespace {

constexpr uint64_t kCursorBytesPerPixel = 4;

bool DescribeAndClearJNIException(JNIEnv* env) {
  if (!env->ExceptionCheck())
    return false;

  env->ExceptionDescribe();
  env->ExceptionClear();
  return true;
}

bool GetCustomCursorBufferSize(const CefCursorInfo& cursor_info, size_t* buffer_size) {
  const int width = cursor_info.size.width;
  const int height = cursor_info.size.height;
  const int hotspot_x = cursor_info.hotspot.x;
  const int hotspot_y = cursor_info.hotspot.y;
  const bool empty = width == 0 && height == 0;
  if (width < 0 || height < 0 || ((width == 0) != (height == 0)) ||
      (empty && (hotspot_x != 0 || hotspot_y != 0)) ||
      (!empty && (hotspot_x < 0 || hotspot_x >= width || hotspot_y < 0 ||
                  hotspot_y >= height)) ||
      !std::isfinite(cursor_info.image_scale_factor) ||
      cursor_info.image_scale_factor <= 0.0f ||
      (!empty && !cursor_info.buffer)) {
    LOG(ERROR) << "Ignoring invalid CEF custom cursor metadata: size=" << width
               << "x" << height << ", hotspot=(" << hotspot_x << ","
               << hotspot_y << "), scale=" << cursor_info.image_scale_factor
               << ", buffer=" << (cursor_info.buffer ? "present" : "null");
    return false;
  }

  const uint64_t pixel_count =
      static_cast<uint64_t>(width) * static_cast<uint64_t>(height);
  const uint64_t byte_count = pixel_count * kCursorBytesPerPixel;
  if (byte_count > static_cast<uint64_t>(std::numeric_limits<jsize>::max())) {
    LOG(ERROR) << "Ignoring CEF custom cursor too large for Java: size="
               << width << "x" << height << ", bytes=" << byte_count;
    return false;
  }

  *buffer_size = static_cast<size_t>(byte_count);
  return true;
}

jobject NewJNICursorInfo(JNIEnv* env, const CefCursorInfo& cursor_info) {
  size_t buffer_size = 0;
  if (!GetCustomCursorBufferSize(cursor_info, &buffer_size))
    return nullptr;

  ScopedJNIObjectLocal buffer(env, NewJNIByteBuffer(env, cursor_info.buffer, buffer_size));
  if (DescribeAndClearJNIException(env) || !buffer) {
    LOG(ERROR) << "Failed to copy CEF custom cursor pixels into Java";
    return nullptr;
  }

  ScopedJNIClass cursor_info_class(env, "org/cef/misc/CefCursorInfo");
  if (DescribeAndClearJNIException(env) || !cursor_info_class) {
    LOG(ERROR) << "Failed to resolve org.cef.misc.CefCursorInfo";
    return nullptr;
  }

  jmethodID create_native = env->GetStaticMethodID(cursor_info_class, "createNative", "(IIFIILjava/nio/ByteBuffer;)Lorg/cef/misc/CefCursorInfo;");
  if (DescribeAndClearJNIException(env) || !create_native) {
    LOG(ERROR) << "Failed to resolve CefCursorInfo.createNative";
    return nullptr;
  }

  jobject result = env->CallStaticObjectMethod(cursor_info_class, create_native, static_cast<jint>(cursor_info.hotspot.x), static_cast<jint>(cursor_info.hotspot.y), static_cast<jfloat>(cursor_info.image_scale_factor), static_cast<jint>(cursor_info.size.width), static_cast<jint>(cursor_info.size.height), buffer.get());
  if (DescribeAndClearJNIException(env) || !result) {
    LOG(ERROR) << "Failed to construct the Java custom cursor snapshot";
    return nullptr;
  }
  return result;
}

}  // namespace

// Java and MCEF consume the raw numeric enum value, so fail the native build if
// CEF changes this wire contract.
static_assert(CT_POINTER == 0);
static_assert(CT_CROSS == 1);
static_assert(CT_HAND == 2);
static_assert(CT_IBEAM == 3);
static_assert(CT_WAIT == 4);
static_assert(CT_HELP == 5);
static_assert(CT_EASTRESIZE == 6);
static_assert(CT_NORTHRESIZE == 7);
static_assert(CT_NORTHEASTRESIZE == 8);
static_assert(CT_NORTHWESTRESIZE == 9);
static_assert(CT_SOUTHRESIZE == 10);
static_assert(CT_SOUTHEASTRESIZE == 11);
static_assert(CT_SOUTHWESTRESIZE == 12);
static_assert(CT_WESTRESIZE == 13);
static_assert(CT_NORTHSOUTHRESIZE == 14);
static_assert(CT_EASTWESTRESIZE == 15);
static_assert(CT_NORTHEASTSOUTHWESTRESIZE == 16);
static_assert(CT_NORTHWESTSOUTHEASTRESIZE == 17);
static_assert(CT_COLUMNRESIZE == 18);
static_assert(CT_ROWRESIZE == 19);
static_assert(CT_MIDDLEPANNING == 20);
static_assert(CT_EASTPANNING == 21);
static_assert(CT_NORTHPANNING == 22);
static_assert(CT_NORTHEASTPANNING == 23);
static_assert(CT_NORTHWESTPANNING == 24);
static_assert(CT_SOUTHPANNING == 25);
static_assert(CT_SOUTHEASTPANNING == 26);
static_assert(CT_SOUTHWESTPANNING == 27);
static_assert(CT_WESTPANNING == 28);
static_assert(CT_MOVE == 29);
static_assert(CT_VERTICALTEXT == 30);
static_assert(CT_CELL == 31);
static_assert(CT_CONTEXTMENU == 32);
static_assert(CT_ALIAS == 33);
static_assert(CT_PROGRESS == 34);
static_assert(CT_NODROP == 35);
static_assert(CT_COPY == 36);
static_assert(CT_NONE == 37);
static_assert(CT_NOTALLOWED == 38);
static_assert(CT_ZOOMIN == 39);
static_assert(CT_ZOOMOUT == 40);
static_assert(CT_GRAB == 41);
static_assert(CT_GRABBING == 42);
static_assert(CT_MIDDLE_PANNING_VERTICAL == 43);
static_assert(CT_MIDDLE_PANNING_HORIZONTAL == 44);
static_assert(CT_CUSTOM == 45);
static_assert(CT_DND_NONE == 46);
static_assert(CT_DND_MOVE == 47);
static_assert(CT_DND_COPY == 48);
static_assert(CT_DND_LINK == 49);
static_assert(CT_NUM_VALUES == 50);

DisplayHandler::DisplayHandler(JNIEnv* env, jobject handler)
    : handle_(env, handler) {}

void DisplayHandler::OnAddressChange(CefRefPtr<CefBrowser> browser,
                                     CefRefPtr<CefFrame> frame,
                                     const CefString& url) {
  ScopedJNIEnv env;
  if (!env)
    return;

  ScopedJNIBrowser jbrowser(env, browser);
  ScopedJNIFrame jframe(env, frame);
  jframe.SetTemporary();
  ScopedJNIString jurl(env, url);

  JNI_CALL_VOID_METHOD(env, handle_, "onAddressChange",
                       "(Lorg/cef/browser/CefBrowser;Lorg/cef/browser/"
                       "CefFrame;Ljava/lang/String;)V",
                       jbrowser.get(), jframe.get(), jurl.get());
}

void DisplayHandler::OnTitleChange(CefRefPtr<CefBrowser> browser,
                                   const CefString& title) {
  ScopedJNIEnv env;
  if (!env)
    return;

  ScopedJNIBrowser jbrowser(env, browser);
  ScopedJNIString jtitle(env, title);

  JNI_CALL_VOID_METHOD(env, handle_, "onTitleChange",
                       "(Lorg/cef/browser/CefBrowser;Ljava/lang/String;)V",
                       jbrowser.get(), jtitle.get());
}

void DisplayHandler::OnFaviconURLChange(CefRefPtr<CefBrowser> browser, const std::vector<CefString>& icon_urls) {
  ScopedJNIEnv env;
  if (!env)
    return;

  ScopedJNIBrowser jbrowser(env, browser);
  ScopedJNIObjectLocal jicon_urls(env, NewJNIStringVector(env, icon_urls));
  if (!jicon_urls)
    return;

  JNI_CALL_VOID_METHOD(env, handle_, "onFaviconURLChange", "(Lorg/cef/browser/CefBrowser;Ljava/util/List;)V", jbrowser.get(), jicon_urls.get());
}

void DisplayHandler::OnFullscreenModeChange(CefRefPtr<CefBrowser> browser,
                                            bool fullscreen) {
  ScopedJNIEnv env;
  if (!env)
    return;

  ScopedJNIBrowser jbrowser(env, browser);
  JNI_CALL_VOID_METHOD(env, handle_, "onFullscreenModeChange",
                       "(Lorg/cef/browser/CefBrowser;Z)V", jbrowser.get(),
                       (jboolean)fullscreen);
}

bool DisplayHandler::OnTooltip(CefRefPtr<CefBrowser> browser, CefString& text) {
  ScopedJNIEnv env;
  if (!env)
    return false;

  ScopedJNIBrowser jbrowser(env, browser);
  ScopedJNIString jtext(env, text);
  jboolean jreturn = JNI_FALSE;

  JNI_CALL_METHOD(env, handle_, "onTooltip",
                  "(Lorg/cef/browser/CefBrowser;Ljava/lang/String;)Z", Boolean,
                  jreturn, jbrowser.get(), jtext.get());

  return (jreturn != JNI_FALSE);
}

void DisplayHandler::OnStatusMessage(CefRefPtr<CefBrowser> browser,
                                     const CefString& value) {
  ScopedJNIEnv env;
  if (!env)
    return;

  ScopedJNIBrowser jbrowser(env, browser);
  ScopedJNIString jvalue(env, value);

  JNI_CALL_VOID_METHOD(env, handle_, "onStatusMessage",
                       "(Lorg/cef/browser/CefBrowser;Ljava/lang/String;)V",
                       jbrowser.get(), jvalue.get());
}

bool DisplayHandler::OnConsoleMessage(CefRefPtr<CefBrowser> browser,
                                      cef_log_severity_t level,
                                      const CefString& message,
                                      const CefString& source,
                                      int line) {
  ScopedJNIEnv env;
  if (!env)
    return false;

  jobject jlevel = nullptr;
  switch (level) {
    JNI_CASE(env, "org/cef/CefSettings$LogSeverity", LOGSEVERITY_VERBOSE,
             jlevel);
    JNI_CASE(env, "org/cef/CefSettings$LogSeverity", LOGSEVERITY_INFO, jlevel);
    JNI_CASE(env, "org/cef/CefSettings$LogSeverity", LOGSEVERITY_WARNING,
             jlevel);
    JNI_CASE(env, "org/cef/CefSettings$LogSeverity", LOGSEVERITY_ERROR, jlevel);
    JNI_CASE(env, "org/cef/CefSettings$LogSeverity", LOGSEVERITY_FATAL, jlevel);
    JNI_CASE(env, "org/cef/CefSettings$LogSeverity", LOGSEVERITY_DISABLE,
             jlevel);
    case LOGSEVERITY_DEFAULT:
      break;
  }

  ScopedJNIBrowser jbrowser(env, browser);
  ScopedJNIString jmessage(env, message);
  ScopedJNIString jsource(env, source);
  jboolean jreturn = JNI_FALSE;

  JNI_CALL_METHOD(
      env, handle_, "onConsoleMessage",
      "(Lorg/cef/browser/CefBrowser;Lorg/cef/CefSettings$LogSeverity;"
      "Ljava/lang/String;Ljava/lang/String;I)Z",
      Boolean, jreturn, jbrowser.get(), jlevel, jmessage.get(), jsource.get(),
      line);

  return (jreturn != JNI_FALSE);
}

void DisplayHandler::OnLoadingProgressChange(CefRefPtr<CefBrowser> browser, double progress) {
  ScopedJNIEnv env;
  if (!env)
    return;

  ScopedJNIBrowser jbrowser(env, browser);
  JNI_CALL_VOID_METHOD(env, handle_, "onLoadingProgressChange", "(Lorg/cef/browser/CefBrowser;D)V", jbrowser.get(), static_cast<jdouble>(progress));
}

bool DisplayHandler::OnCursorChange(CefRefPtr<CefBrowser> browser,
                                    CefCursorHandle cursor,
                                    cef_cursor_type_t type,
                                    const CefCursorInfo& custom_cursor_info) {
  ScopedJNIEnv env;
  if (!env)
    return false;

  ScopedJNIBrowser jbrowser(env, browser);
  const int cursorId = (int) type;
  // CefCursorHandle is an operating-system-specific value owned by CEF's
  // callback-scoped handle wrapper. Java cannot portably interpret or safely
  // retain it, so only the owned custom bitmap snapshot crosses JNI.
  static_cast<void>(cursor);
  ScopedJNIObjectLocal jcustom_cursor_info(env, type == CT_CUSTOM ? NewJNICursorInfo(env, custom_cursor_info) : nullptr);
  jboolean jreturn = JNI_FALSE;

  JNI_CALL_METHOD(env, handle_, "onCursorChange", "(Lorg/cef/browser/CefBrowser;ILorg/cef/misc/CefCursorInfo;)Z", Boolean, jreturn, jbrowser.get(), cursorId, jcustom_cursor_info.get());

  return (jreturn != JNI_FALSE);
}
