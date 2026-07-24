// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#ifndef JCEF_NATIVE_MOUSE_WHEEL_PLATFORM_UTIL_H_
#define JCEF_NATIVE_MOUSE_WHEEL_PLATFORM_UTIL_H_

#include <cstdint>

namespace mouse_wheel_platform_util {

enum class WindowsCefWheelDeltaStatus {
  kSuccess,
  kApproximated,
  kScrollingDisabled,
  kPageScrolling,
};

struct WindowsCefWheelDelta {
  // Raw integer passed to CefBrowserHost and the integer its Windows delegate
  // will produce after applying the current system setting.
  int delta;
  int translated_delta;
  WindowsCefWheelDeltaStatus status;
};

// Rounds a non-zero finite magnitude away from zero when it would otherwise
// disappear at CefBrowserHost's integer wheel-delta boundary.
int RoundNonZeroWheelDelta(double delta);

// Returns the signed pixel delta that CEF's Windows delegate should produce
// before Chromium converts it to a DOM WheelEvent. AWT defines precise unit
// scrolling as preciseWheelRotation * scrollAmount; CEF 151 models one Windows
// scroll unit as 100/3 pixels.
int GetWindowsAwtUnitTargetDelta(double precise_rotation, int scroll_amount);

// Finds an integer CefBrowserHost delta whose CEF 151 Windows transform
// produces target_delta for the supplied line/character setting. If exact
// inversion is impossible, the closest safe non-zero result with the same sign
// is returned as kApproximated, and translated_delta exposes the bounded error.
// Every non-zero, non-page setting has a representable same-sign input, so the
// result is always kSuccess or kApproximated for those settings.
// The transform intentionally uses float because CEF truncates gfx::Vector2d.
WindowsCefWheelDelta InvertWindowsCefWheelDelta(int target_delta, std::uint32_t scroll_units);

// Reads the same current Windows line/character setting as CEF and inverts the
// corresponding axis transform. This function is only linked on Windows.
WindowsCefWheelDelta GetWindowsCefWheelDelta(int target_delta, bool horizontal);

}  // namespace mouse_wheel_platform_util

#endif  // JCEF_NATIVE_MOUSE_WHEEL_PLATFORM_UTIL_H_
