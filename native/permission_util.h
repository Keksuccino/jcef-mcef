// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#ifndef JCEF_NATIVE_PERMISSION_UTIL_H_
#define JCEF_NATIVE_PERMISSION_UTIL_H_
#pragma once

#include <atomic>
#include <bit>
#include <cstdint>

#include <jni.h>

#include "include/cef_permission_handler.h"

namespace permission_util {

static_assert(sizeof(jint) == sizeof(uint32_t));
static_assert(sizeof(jlong) == sizeof(uint64_t));
static_assert(CEF_API_VERSION == 15100);

static_assert(CEF_MEDIA_PERMISSION_NONE == 0);
static_assert(CEF_MEDIA_PERMISSION_DEVICE_AUDIO_CAPTURE == (1 << 0));
static_assert(CEF_MEDIA_PERMISSION_DEVICE_VIDEO_CAPTURE == (1 << 1));
static_assert(CEF_MEDIA_PERMISSION_DESKTOP_AUDIO_CAPTURE == (1 << 2));
static_assert(CEF_MEDIA_PERMISSION_DESKTOP_VIDEO_CAPTURE == (1 << 3));

static_assert(CEF_PERMISSION_TYPE_NONE == 0);
static_assert(CEF_PERMISSION_TYPE_AR_SESSION == (1 << 0));
static_assert(CEF_PERMISSION_TYPE_CAMERA_PAN_TILT_ZOOM == (1 << 1));
static_assert(CEF_PERMISSION_TYPE_CAMERA_STREAM == (1 << 2));
static_assert(CEF_PERMISSION_TYPE_CAPTURED_SURFACE_CONTROL == (1 << 3));
static_assert(CEF_PERMISSION_TYPE_CLIPBOARD == (1 << 4));
static_assert(CEF_PERMISSION_TYPE_TOP_LEVEL_STORAGE_ACCESS == (1 << 5));
static_assert(CEF_PERMISSION_TYPE_DISK_QUOTA == (1 << 6));
static_assert(CEF_PERMISSION_TYPE_LOCAL_FONTS == (1 << 7));
static_assert(CEF_PERMISSION_TYPE_GEOLOCATION == (1 << 8));
static_assert(CEF_PERMISSION_TYPE_HAND_TRACKING == (1 << 9));
static_assert(CEF_PERMISSION_TYPE_IDENTITY_PROVIDER == (1 << 10));
static_assert(CEF_PERMISSION_TYPE_IDLE_DETECTION == (1 << 11));
static_assert(CEF_PERMISSION_TYPE_MIC_STREAM == (1 << 12));
static_assert(CEF_PERMISSION_TYPE_MIDI_SYSEX == (1 << 13));
static_assert(CEF_PERMISSION_TYPE_MULTIPLE_DOWNLOADS == (1 << 14));
static_assert(CEF_PERMISSION_TYPE_NOTIFICATIONS == (1 << 15));
static_assert(CEF_PERMISSION_TYPE_KEYBOARD_LOCK == (1 << 16));
static_assert(CEF_PERMISSION_TYPE_POINTER_LOCK == (1 << 17));
static_assert(CEF_PERMISSION_TYPE_PROTECTED_MEDIA_IDENTIFIER == (1 << 18));
static_assert(CEF_PERMISSION_TYPE_REGISTER_PROTOCOL_HANDLER == (1 << 19));
static_assert(CEF_PERMISSION_TYPE_STORAGE_ACCESS == (1 << 20));
static_assert(CEF_PERMISSION_TYPE_VR_SESSION == (1 << 21));
static_assert(CEF_PERMISSION_TYPE_WEB_APP_INSTALLATION == (1 << 22));
static_assert(CEF_PERMISSION_TYPE_WINDOW_MANAGEMENT == (1 << 23));
static_assert(CEF_PERMISSION_TYPE_FILE_SYSTEM_ACCESS == (1 << 24));
static_assert(CEF_PERMISSION_TYPE_LOCAL_NETWORK_ACCESS_DEPRECATED == (1 << 25));
static_assert(CEF_PERMISSION_TYPE_LOCAL_NETWORK == (1 << 26));
static_assert(CEF_PERMISSION_TYPE_LOOPBACK_NETWORK == (1 << 27));
static_assert(CEF_PERMISSION_TYPE_SENSORS == (1 << 28));

static_assert(CEF_PERMISSION_RESULT_ACCEPT == 0);
static_assert(CEF_PERMISSION_RESULT_DENY == 1);
static_assert(CEF_PERMISSION_RESULT_DISMISS == 2);
static_assert(CEF_PERMISSION_RESULT_IGNORE == 3);
static_assert(CEF_PERMISSION_RESULT_NUM_VALUES == 4);

// Bit-casts, rather than numeric casts, preserve future high permission bits
// and unsigned prompt identifiers exactly across Java's signed primitive types.
constexpr jint PermissionMaskToJNI(uint32_t permissions) {
  return std::bit_cast<jint>(permissions);
}

constexpr uint32_t PermissionMaskFromJNI(jint permissions) {
  return std::bit_cast<uint32_t>(permissions);
}

constexpr jlong PromptIdToJNI(uint64_t prompt_id) {
  return std::bit_cast<jlong>(prompt_id);
}

constexpr uint64_t PromptIdFromJNI(jlong prompt_id) {
  return std::bit_cast<uint64_t>(prompt_id);
}

constexpr bool IsValidPermissionResult(jint result) {
  return result >= CEF_PERMISSION_RESULT_ACCEPT &&
         result < CEF_PERMISSION_RESULT_NUM_VALUES;
}

// Invalid application values fail closed. IGNORE never represents a user grant
// and matches CEF's safe Alloy default for an unhandled general permission
// prompt.
constexpr cef_permission_request_result_t PermissionResultFromJNI(jint result) {
  return IsValidPermissionResult(result)
             ? static_cast<cef_permission_request_result_t>(result)
             : CEF_PERMISSION_RESULT_IGNORE;
}

constexpr jint PermissionResultToJNI(cef_permission_request_result_t result) {
  return static_cast<jint>(result);
}

class OneShotGate final {
 public:
  bool TryClaim() {
    bool expected = false;
    return claimed_.compare_exchange_strong(expected, true, std::memory_order_acq_rel, std::memory_order_acquire);
  }

  bool IsClaimed() const { return claimed_.load(std::memory_order_acquire); }

 private:
  std::atomic<bool> claimed_{false};
};

}  // namespace permission_util

#endif  // JCEF_NATIVE_PERMISSION_UTIL_H_
