// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "mouse_wheel_platform_util.h"

#include <windows.h>

namespace mouse_wheel_platform_util {

WindowsCefWheelDelta GetWindowsCefWheelDelta(int target_delta, bool horizontal) {
  // Match CEF 151's defaults when SystemParametersInfo fails; its Windows
  // delegate ignores the return value and keeps one character or three lines.
  ULONG scroll_units = horizontal ? 1 : 3;
  SystemParametersInfo(horizontal ? SPI_GETWHEELSCROLLCHARS : SPI_GETWHEELSCROLLLINES, 0, &scroll_units, 0);
  return InvertWindowsCefWheelDelta(target_delta, static_cast<std::uint32_t>(scroll_units));
}

}  // namespace mouse_wheel_platform_util
