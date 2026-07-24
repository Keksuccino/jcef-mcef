// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "mouse_wheel_platform_util.h"

#include <cmath>
#include <limits>
#include <string_view>

#include "include/cef_version.h"

namespace mouse_wheel_platform_util {
namespace {

static_assert(std::string_view(CEF_VERSION) == "151.2.3+g89cd581+chromium-151.0.7922.34" && CEF_COMMIT_NUMBER == 3553 && std::string_view(CEF_COMMIT_HASH) == "89cd5813e47d84c68e56ced336c2c01b7dc77b8d" && CHROME_VERSION_MAJOR == 151 && CHROME_VERSION_MINOR == 0 && CHROME_VERSION_BUILD == 7922 && CHROME_VERSION_PATCH == 34, "CEF changed: re-audit CefBrowserPlatformDelegateNativeWin::GetUiWheelEventOffset before updating the mirrored AWT wheel transform");

constexpr float kWindowsWheelDelta = 120.0f;
constexpr float kWindowsPixelsPerScrollUnit = 100.0f / 3.0f;
constexpr float kIntMinimumAsFloat = -2147483648.0f;
constexpr float kIntMaximumExclusiveAsFloat = 2147483648.0f;
constexpr std::uint32_t kWindowsPageScroll = std::numeric_limits<std::uint32_t>::max();

enum class TranslationRange { kBelowInt, kInInt, kAboveInt };

struct TranslatedDelta {
  int delta;
  TranslationRange range;
};

// Keep these operations separate and in this order. They intentionally match
// CefBrowserPlatformDelegateNativeWin::GetUiWheelEventOffset in CEF 151,
// including its float precision and truncating float-to-int conversion.
constexpr TranslatedDelta TranslateWindowsCefWheelDelta(int input_delta, std::uint32_t scroll_units) {
  float wheel_delta = static_cast<float>(input_delta) / kWindowsWheelDelta;
  float translated_delta = wheel_delta;
  translated_delta *=
      static_cast<float>(scroll_units) * kWindowsPixelsPerScrollUnit;
  if (translated_delta < kIntMinimumAsFloat)
    return {0, TranslationRange::kBelowInt};
  if (translated_delta >= kIntMaximumExclusiveAsFloat)
    return {0, TranslationRange::kAboveInt};
  return {static_cast<int>(translated_delta), TranslationRange::kInInt};
}

constexpr int CompareTranslatedDelta(const TranslatedDelta& translated, int target_delta) {
  if (translated.range == TranslationRange::kBelowInt)
    return -1;
  if (translated.range == TranslationRange::kAboveInt)
    return 1;
  if (translated.delta < target_delta)
    return -1;
  if (translated.delta > target_delta)
    return 1;
  return 0;
}

constexpr bool HasSameNonZeroSign(int value, int target) {
  return (value > 0 && target > 0) || (value < 0 && target < 0);
}

constexpr std::uint64_t GetDeltaDistance(int first, int second) {
  const std::int64_t difference =
      static_cast<std::int64_t>(first) - static_cast<std::int64_t>(second);
  return difference < 0 ? static_cast<std::uint64_t>(-difference)
                        : static_cast<std::uint64_t>(difference);
}

constexpr std::uint64_t GetDeltaMagnitude(int value) {
  const std::int64_t wide_value = value;
  return wide_value < 0 ? static_cast<std::uint64_t>(-wide_value)
                        : static_cast<std::uint64_t>(wide_value);
}

constexpr WindowsCefWheelDelta SelectCloserApproximation(WindowsCefWheelDelta current, int input_delta, const TranslatedDelta& translated, int target_delta) {
  if (translated.range != TranslationRange::kInInt ||
      !HasSameNonZeroSign(translated.delta, target_delta))
    return current;

  const std::uint64_t current_distance = GetDeltaDistance(current.translated_delta, target_delta);
  const std::uint64_t candidate_distance = GetDeltaDistance(translated.delta, target_delta);
  if (candidate_distance < current_distance ||
      (candidate_distance == current_distance &&
       GetDeltaMagnitude(translated.delta) < GetDeltaMagnitude(current.translated_delta))) {
    return {input_delta, translated.delta,
            WindowsCefWheelDeltaStatus::kApproximated};
  }
  return current;
}

constexpr int GetGuaranteedVisibleInput(int target_delta, std::uint32_t scroll_units) {
  // The CEF transform is monotonic for positive scroll settings. Raw magnitude
  // 4 produces one pixel at one unit, magnitude 2 does so at two and three
  // units, and magnitude 1 does so from four through UINT_MAX - 1. At that
  // upper boundary the translated magnitude remains below INT_MAX. Seeding the
  // approximation with this invariant removes the need for an impossible
  // public overflow result while keeping the search independently defensive.
  const int magnitude = scroll_units == 1 ? 4 : (scroll_units <= 3 ? 2 : 1);
  return target_delta > 0 ? magnitude : -magnitude;
}

constexpr WindowsCefWheelDelta InvertWindowsCefWheelDeltaImpl(int target_delta, std::uint32_t scroll_units) {
  if (target_delta == 0)
    return {0, 0, WindowsCefWheelDeltaStatus::kSuccess};
  if (scroll_units == 0)
    return {0, 0, WindowsCefWheelDeltaStatus::kScrollingDisabled};
  if (scroll_units == kWindowsPageScroll)
    return {0, 0, WindowsCefWheelDeltaStatus::kPageScrolling};

  std::int64_t low = std::numeric_limits<int>::min();
  std::int64_t high = std::numeric_limits<int>::max();
  if (target_delta > 0) {
    // Find the smallest positive input in the target's truncation bucket.
    while (low < high) {
      const std::int64_t middle = low + (high - low) / 2;
      if (CompareTranslatedDelta(TranslateWindowsCefWheelDelta(static_cast<int>(middle), scroll_units), target_delta) < 0)
        low = middle + 1;
      else
        high = middle;
    }
  } else {
    // Find the largest negative input in the target's truncation bucket. This
    // keeps positive and negative inversions symmetric around zero.
    while (low < high) {
      const std::int64_t middle = low + (high - low + 1) / 2;
      if (CompareTranslatedDelta(TranslateWindowsCefWheelDelta(static_cast<int>(middle), scroll_units), target_delta) > 0)
        high = middle - 1;
      else
        low = middle;
    }
  }

  const int candidate = static_cast<int>(low);
  const TranslatedDelta translated = TranslateWindowsCefWheelDelta(candidate, scroll_units);
  if (translated.range == TranslationRange::kInInt &&
      translated.delta == target_delta)
    return {candidate, translated.delta, WindowsCefWheelDeltaStatus::kSuccess};

  // Integer truncation can skip small target magnitudes when the system scroll
  // setting is large. Preserve delivery by choosing the closest adjacent safe
  // bucket with the same sign; ties prefer the smaller visible magnitude and
  // make no promise about the raw input within an equivalent float plateau.
  const int guaranteed_input = GetGuaranteedVisibleInput(target_delta, scroll_units);
  const TranslatedDelta guaranteed_translation = TranslateWindowsCefWheelDelta(guaranteed_input, scroll_units);
  WindowsCefWheelDelta approximation = {
      guaranteed_input, guaranteed_translation.delta,
      WindowsCefWheelDeltaStatus::kApproximated};
  approximation = SelectCloserApproximation(approximation, candidate, translated, target_delta);
  if (target_delta > 0 && candidate > std::numeric_limits<int>::min()) {
    const int adjacent = candidate - 1;
    approximation = SelectCloserApproximation(approximation, adjacent, TranslateWindowsCefWheelDelta(adjacent, scroll_units), target_delta);
  } else if (target_delta < 0 && candidate < std::numeric_limits<int>::max()) {
    const int adjacent = candidate + 1;
    approximation = SelectCloserApproximation(approximation, adjacent, TranslateWindowsCefWheelDelta(adjacent, scroll_units), target_delta);
  }
  return approximation;
}

// Compile-time cases keep the mirrored CEF transform independently testable on
// every build host, including when a Windows runtime is unavailable.
constexpr WindowsCefWheelDelta kRepresentableUnitDelta = InvertWindowsCefWheelDeltaImpl(-53, 3);
constexpr WindowsCefWheelDelta kRepresentableMinimumDelta = InvertWindowsCefWheelDeltaImpl(-1, 3);
constexpr WindowsCefWheelDelta kRepresentableHorizontalMinimumDelta = InvertWindowsCefWheelDeltaImpl(-1, 1);
constexpr WindowsCefWheelDelta kRepresentablePageDelta = InvertWindowsCefWheelDeltaImpl(-3, 3);
constexpr WindowsCefWheelDelta kApproximatedMinimumDelta = InvertWindowsCefWheelDeltaImpl(1, 10);
constexpr WindowsCefWheelDelta kApproximatedNegativeMinimumDelta = InvertWindowsCefWheelDeltaImpl(-1, 10);
constexpr WindowsCefWheelDelta kApproximatedPageDelta = InvertWindowsCefWheelDeltaImpl(3, 10);
constexpr WindowsCefWheelDelta kPositiveBoundaryDelta = InvertWindowsCefWheelDeltaImpl(std::numeric_limits<int>::max(), std::numeric_limits<std::uint32_t>::max() - 1);
constexpr WindowsCefWheelDelta kNegativeBoundaryDelta = InvertWindowsCefWheelDeltaImpl(std::numeric_limits<int>::min(), std::numeric_limits<std::uint32_t>::max() - 1);
static_assert(kRepresentableUnitDelta.status ==
                  WindowsCefWheelDeltaStatus::kSuccess &&
              kRepresentableUnitDelta.delta == -64);
static_assert(kRepresentableMinimumDelta.status ==
                  WindowsCefWheelDeltaStatus::kSuccess &&
              kRepresentableMinimumDelta.delta == -2);
static_assert(kRepresentableHorizontalMinimumDelta.status ==
                  WindowsCefWheelDeltaStatus::kSuccess &&
              kRepresentableHorizontalMinimumDelta.delta == -4);
static_assert(kRepresentablePageDelta.status ==
                  WindowsCefWheelDeltaStatus::kSuccess &&
              kRepresentablePageDelta.delta == -4);
static_assert(kApproximatedMinimumDelta.status ==
                  WindowsCefWheelDeltaStatus::kApproximated &&
              kApproximatedMinimumDelta.delta == 1 &&
              kApproximatedMinimumDelta.translated_delta == 2);
static_assert(kApproximatedNegativeMinimumDelta.status ==
                  WindowsCefWheelDeltaStatus::kApproximated &&
              kApproximatedNegativeMinimumDelta.delta == -1 &&
              kApproximatedNegativeMinimumDelta.translated_delta == -2);
static_assert(kApproximatedPageDelta.status ==
                  WindowsCefWheelDeltaStatus::kApproximated &&
              kApproximatedPageDelta.delta == 1 &&
              kApproximatedPageDelta.translated_delta == 2);
static_assert(TranslateWindowsCefWheelDelta(std::numeric_limits<int>::max(), 10)
                  .range == TranslationRange::kAboveInt);
static_assert(TranslateWindowsCefWheelDelta(4, 1).delta == 1 &&
              TranslateWindowsCefWheelDelta(-4, 1).delta == -1);
static_assert(TranslateWindowsCefWheelDelta(2, 2).delta == 1 &&
              TranslateWindowsCefWheelDelta(-2, 2).delta == -1);
static_assert(TranslateWindowsCefWheelDelta(2, 3).delta == 1 &&
              TranslateWindowsCefWheelDelta(-2, 3).delta == -1);
static_assert(TranslateWindowsCefWheelDelta(1, 4).delta == 1 &&
              TranslateWindowsCefWheelDelta(-1, 4).delta == -1);
static_assert(TranslateWindowsCefWheelDelta(1, std::numeric_limits<std::uint32_t>::max() - 1).range == TranslationRange::kInInt && TranslateWindowsCefWheelDelta(1, std::numeric_limits<std::uint32_t>::max() - 1).delta > 0);
static_assert(TranslateWindowsCefWheelDelta(-1, std::numeric_limits<std::uint32_t>::max() - 1).range == TranslationRange::kInInt && TranslateWindowsCefWheelDelta(-1, std::numeric_limits<std::uint32_t>::max() - 1).delta < 0);
static_assert(kPositiveBoundaryDelta.status ==
                  WindowsCefWheelDeltaStatus::kApproximated &&
              kPositiveBoundaryDelta.translated_delta > 0);
static_assert(kNegativeBoundaryDelta.status ==
                  WindowsCefWheelDeltaStatus::kApproximated &&
              kNegativeBoundaryDelta.translated_delta < 0);
static_assert(InvertWindowsCefWheelDeltaImpl(1, 0).status ==
              WindowsCefWheelDeltaStatus::kScrollingDisabled);
static_assert(InvertWindowsCefWheelDeltaImpl(1, kWindowsPageScroll).status ==
              WindowsCefWheelDeltaStatus::kPageScrolling);

}  // namespace

int RoundNonZeroWheelDelta(double delta) {
  if (std::isnan(delta) || delta == 0)
    return 0;
  if (delta >= std::numeric_limits<int>::max())
    return std::numeric_limits<int>::max();
  if (delta <= std::numeric_limits<int>::min())
    return std::numeric_limits<int>::min();
  const long rounded = std::lround(delta);
  if (rounded == 0)
    return std::signbit(delta) ? -1 : 1;
  return static_cast<int>(rounded);
}

int GetWindowsAwtUnitTargetDelta(double precise_rotation, int scroll_amount) {
  return RoundNonZeroWheelDelta(-precise_rotation * static_cast<double>(scroll_amount) * static_cast<double>(kWindowsPixelsPerScrollUnit));
}

WindowsCefWheelDelta InvertWindowsCefWheelDelta(int target_delta, std::uint32_t scroll_units) {
  return InvertWindowsCefWheelDeltaImpl(target_delta, scroll_units);
}

}  // namespace mouse_wheel_platform_util
