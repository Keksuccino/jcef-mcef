// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "mouse_wheel_platform_util.h"

#include <cmath>
#include <cstdint>
#include <iostream>
#include <limits>

namespace {

using mouse_wheel_platform_util::WindowsCefWheelDelta;
using mouse_wheel_platform_util::WindowsCefWheelDeltaStatus;

int failure_count = 0;

void Expect(bool condition, const char* description) {
  if (condition)
    return;
  std::cerr << "FAILED: " << description << std::endl;
  ++failure_count;
}

void ExpectDelta(int target_delta, std::uint32_t scroll_units, int expected_delta, int expected_translated_delta, WindowsCefWheelDeltaStatus expected_status, const char* description) {
  const WindowsCefWheelDelta result = mouse_wheel_platform_util::InvertWindowsCefWheelDelta(target_delta, scroll_units);
  Expect(result.delta == expected_delta, description);
  Expect(result.translated_delta == expected_translated_delta, description);
  Expect(result.status == expected_status, description);
}

void TestNonZeroRounding() {
  Expect(mouse_wheel_platform_util::RoundNonZeroWheelDelta(0.0) == 0, "zero wheel delta remains zero");
  Expect(mouse_wheel_platform_util::RoundNonZeroWheelDelta(std::numeric_limits<double>::quiet_NaN()) == 0, "NaN wheel delta is rejected");
  Expect(mouse_wheel_platform_util::RoundNonZeroWheelDelta(0.001) == 1, "positive sub-integer wheel delta remains visible");
  Expect(mouse_wheel_platform_util::RoundNonZeroWheelDelta(-0.001) == -1, "negative sub-integer wheel delta remains visible");
  Expect(mouse_wheel_platform_util::RoundNonZeroWheelDelta(1.5) == 2, "positive wheel delta uses nearest rounding");
  Expect(mouse_wheel_platform_util::RoundNonZeroWheelDelta(-1.5) == -2, "negative wheel delta uses nearest rounding");
  Expect(mouse_wheel_platform_util::RoundNonZeroWheelDelta(std::numeric_limits<double>::infinity()) == std::numeric_limits<int>::max(), "positive overflow saturates");
  Expect(mouse_wheel_platform_util::RoundNonZeroWheelDelta(-std::numeric_limits<double>::infinity()) == std::numeric_limits<int>::min(), "negative overflow saturates");
}

void TestAwtUnitTargetDelta() {
  Expect(mouse_wheel_platform_util::GetWindowsAwtUnitTargetDelta(0.4, 4) == -53, "AWT unit distance converts to CEF pixel distance");
  Expect(mouse_wheel_platform_util::GetWindowsAwtUnitTargetDelta(-0.001, 1) == 1, "minimum AWT unit distance remains visible");
  Expect(mouse_wheel_platform_util::GetWindowsAwtUnitTargetDelta(1.0, 0) == 0, "zero AWT scroll amount remains zero");
}

void TestInversionStatuses() {
  ExpectDelta(-53, 3, -64, -53, WindowsCefWheelDeltaStatus::kSuccess, "exact Windows wheel inversion");
  ExpectDelta(1, 10, 1, 2, WindowsCefWheelDeltaStatus::kApproximated, "positive quantized Windows wheel inversion");
  ExpectDelta(-1, 10, -1, -2, WindowsCefWheelDeltaStatus::kApproximated, "negative quantized Windows wheel inversion");
  ExpectDelta(1, 0, 0, 0, WindowsCefWheelDeltaStatus::kScrollingDisabled, "disabled Windows wheel setting");
  ExpectDelta(1, std::numeric_limits<std::uint32_t>::max(), 0, 0, WindowsCefWheelDeltaStatus::kPageScrolling, "WHEEL_PAGESCROLL Windows wheel setting");
  ExpectDelta(0, 0, 0, 0, WindowsCefWheelDeltaStatus::kSuccess, "zero target requires no Windows wheel input");
}

void TestEveryNormalSettingHasSafeApproximation() {
  const WindowsCefWheelDelta low_scale_positive = mouse_wheel_platform_util::InvertWindowsCefWheelDelta(std::numeric_limits<int>::max(), 1);
  Expect(low_scale_positive.status == WindowsCefWheelDeltaStatus::kApproximated, "positive low-scale extreme is approximated");
  Expect(low_scale_positive.translated_delta == 596523264, "positive low-scale extreme chooses the closest visible plateau");

  const WindowsCefWheelDelta low_scale_negative = mouse_wheel_platform_util::InvertWindowsCefWheelDelta(std::numeric_limits<int>::min(), 1);
  Expect(low_scale_negative.status == WindowsCefWheelDeltaStatus::kApproximated, "negative low-scale extreme is approximated");
  Expect(low_scale_negative.translated_delta == -596523264, "negative low-scale extreme chooses the closest visible plateau");

  constexpr std::uint32_t kLargestNormalScrollSetting = std::numeric_limits<std::uint32_t>::max() - 1;
  const WindowsCefWheelDelta positive = mouse_wheel_platform_util::InvertWindowsCefWheelDelta(std::numeric_limits<int>::max(), kLargestNormalScrollSetting);
  Expect(positive.status == WindowsCefWheelDeltaStatus::kApproximated, "positive boundary target is safely approximated");
  Expect(positive.delta > 0 && positive.translated_delta > 0, "positive boundary approximation preserves sign");

  const WindowsCefWheelDelta negative = mouse_wheel_platform_util::InvertWindowsCefWheelDelta(std::numeric_limits<int>::min(), kLargestNormalScrollSetting);
  Expect(negative.status == WindowsCefWheelDeltaStatus::kApproximated, "negative boundary target is safely approximated");
  Expect(negative.delta < 0 && negative.translated_delta < 0, "negative boundary approximation preserves sign");

  // These four transition points cover the constructive seed used by the
  // implementation: raw 4 at one unit, raw 2 at two and three units, and raw
  // 1 for the entire remaining range through UINT_MAX - 1.
  ExpectDelta(1, 1, 4, 1, WindowsCefWheelDeltaStatus::kSuccess, "one-unit minimum visible input");
  ExpectDelta(1, 2, 2, 1, WindowsCefWheelDeltaStatus::kSuccess, "two-unit minimum visible input");
  ExpectDelta(1, 3, 2, 1, WindowsCefWheelDeltaStatus::kSuccess, "three-unit minimum visible input");
  ExpectDelta(1, 4, 1, 1, WindowsCefWheelDeltaStatus::kSuccess, "four-unit minimum visible input");
}

}  // namespace

int main() {
  TestNonZeroRounding();
  TestAwtUnitTargetDelta();
  TestInversionStatuses();
  TestEveryNormalSettingHasSafeApproximation();
  if (failure_count != 0) {
    std::cerr << failure_count << " mouse wheel platform utility assertions failed" << std::endl;
    return 1;
  }
  std::cout << "All mouse wheel platform utility assertions passed" << std::endl;
  return 0;
}
