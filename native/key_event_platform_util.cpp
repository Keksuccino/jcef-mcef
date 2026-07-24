// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "key_event_platform_util.h"

#include <limits>

namespace key_event_platform_util {
namespace {

// These values are stable public ABI constants from java.awt.event and GLFW.
// Keeping the domains local avoids runtime dependencies on either toolkit.
namespace awt {
constexpr int kKeyLocationRight = 3;
constexpr int kKeyLocationNumpad = 4;
constexpr int kVkCancel = 3;
constexpr int kVkBackSpace = 8;
constexpr int kVkTab = 9;
constexpr int kVkEnter = 10;
constexpr int kVkClear = 12;
constexpr int kVkShift = 16;
constexpr int kVkControl = 17;
constexpr int kVkAlt = 18;
constexpr int kVkPause = 19;
constexpr int kVkCapsLock = 20;
constexpr int kVkEscape = 27;
constexpr int kVkSpace = 32;
constexpr int kVkPageUp = 33;
constexpr int kVkPageDown = 34;
constexpr int kVkEnd = 35;
constexpr int kVkHome = 36;
constexpr int kVkLeft = 37;
constexpr int kVkUp = 38;
constexpr int kVkRight = 39;
constexpr int kVkDown = 40;
constexpr int kVkNumpad0 = 96;
constexpr int kVkNumpad9 = 105;
constexpr int kVkMultiply = 106;
constexpr int kVkAdd = 107;
constexpr int kVkSeparator = 108;
constexpr int kVkSubtract = 109;
constexpr int kVkDecimal = 110;
constexpr int kVkDivide = 111;
constexpr int kVkF1 = 112;
constexpr int kVkF12 = 123;
constexpr int kVkDelete = 127;
constexpr int kVkDeadGrave = 128;
constexpr int kVkDeadSemivoicedSound = 143;
constexpr int kVkNumLock = 144;
constexpr int kVkScrollLock = 145;
constexpr int kVkPrintScreen = 154;
constexpr int kVkInsert = 155;
constexpr int kVkHelp = 156;
constexpr int kVkMeta = 157;
constexpr int kVkBackQuote = 192;
constexpr int kVkQuote = 222;
constexpr int kVkKpUp = 224;
constexpr int kVkKpDown = 225;
constexpr int kVkKpLeft = 226;
constexpr int kVkKpRight = 227;
constexpr int kVkWindows = 524;
constexpr int kVkContextMenu = 525;
constexpr int kVkF13 = 61440;
constexpr int kVkF24 = 61451;
constexpr int kVkCompose = 65312;
constexpr int kVkAltGraph = 65406;
}  // namespace awt

namespace glfw {
constexpr int kKeyEscape = 256;
constexpr int kKeyEnter = 257;
constexpr int kKeyTab = 258;
constexpr int kKeyBackspace = 259;
constexpr int kKeyInsert = 260;
constexpr int kKeyDelete = 261;
constexpr int kKeyRight = 262;
constexpr int kKeyLeft = 263;
constexpr int kKeyDown = 264;
constexpr int kKeyUp = 265;
constexpr int kKeyPageUp = 266;
constexpr int kKeyPageDown = 267;
constexpr int kKeyHome = 268;
constexpr int kKeyEnd = 269;
constexpr int kKeyCapsLock = 280;
constexpr int kKeyScrollLock = 281;
constexpr int kKeyNumLock = 282;
constexpr int kKeyPrintScreen = 283;
constexpr int kKeyPause = 284;
constexpr int kKeyF1 = 290;
constexpr int kKeyF25 = 314;
constexpr int kKeyKp0 = 320;
constexpr int kKeyKp9 = 329;
constexpr int kKeyKpDecimal = 330;
constexpr int kKeyKpDivide = 331;
constexpr int kKeyKpMultiply = 332;
constexpr int kKeyKpSubtract = 333;
constexpr int kKeyKpAdd = 334;
constexpr int kKeyKpEnter = 335;
constexpr int kKeyKpEqual = 336;
constexpr int kKeyLeftShift = 340;
constexpr int kKeyLeftControl = 341;
constexpr int kKeyLeftAlt = 342;
constexpr int kKeyLeftSuper = 343;
constexpr int kKeyRightShift = 344;
constexpr int kKeyRightControl = 345;
constexpr int kKeyRightAlt = 346;
constexpr int kKeyRightSuper = 347;
constexpr int kKeyMenu = 348;
}  // namespace glfw

// Chromium interprets CefKeyEvent.native_key_code on Linux as an XKB hardware
// keycode (the evdev code plus 8), not as an X keysym. These constants come
// from the XKB column of Chromium 151's
// ui/events/keycodes/dom/dom_code_data.inc. This table must remain independent
// of an X display because Java event delivery need not run on CEF's UI thread.
namespace linux_xkb {
constexpr int kUnknown = 0;
constexpr int kEscape = 9;
constexpr int kDigit1 = 10;
constexpr int kDigit0 = 19;
constexpr int kMinus = 20;
constexpr int kEqual = 21;
constexpr int kBackspace = 22;
constexpr int kTab = 23;
constexpr int kBracketLeft = 34;
constexpr int kBracketRight = 35;
constexpr int kEnter = 36;
constexpr int kControlLeft = 37;
constexpr int kSemicolon = 47;
constexpr int kQuote = 48;
constexpr int kBackquote = 49;
constexpr int kShiftLeft = 50;
constexpr int kBackslash = 51;
constexpr int kShiftRight = 62;
constexpr int kNumpadMultiply = 63;
constexpr int kAltLeft = 64;
constexpr int kSpace = 65;
constexpr int kCapsLock = 66;
constexpr int kF1 = 67;
constexpr int kNumLock = 77;
constexpr int kScrollLock = 78;
constexpr int kNumpadSubtract = 82;
constexpr int kNumpadAdd = 86;
constexpr int kNumpadDecimal = 91;
constexpr int kF11 = 95;
constexpr int kNumpadEnter = 104;
constexpr int kControlRight = 105;
constexpr int kNumpadDivide = 106;
constexpr int kPrintScreen = 107;
constexpr int kAltRight = 108;
constexpr int kHome = 110;
constexpr int kArrowUp = 111;
constexpr int kPageUp = 112;
constexpr int kArrowLeft = 113;
constexpr int kArrowRight = 114;
constexpr int kEnd = 115;
constexpr int kArrowDown = 116;
constexpr int kPageDown = 117;
constexpr int kInsert = 118;
constexpr int kDelete = 119;
constexpr int kNumpadEqual = 125;
constexpr int kPause = 127;
constexpr int kNumpadComma = 129;
constexpr int kMetaLeft = 133;
constexpr int kMetaRight = 134;
constexpr int kContextMenu = 135;
constexpr int kHelp = 146;
constexpr int kF13 = 191;

constexpr int kLetterCodes[] = {38, 56, 54, 40, 26, 41, 42, 43, 31,
                                44, 45, 46, 58, 57, 32, 33, 24, 27,
                                39, 28, 30, 55, 25, 53, 29, 52};
constexpr int kNumpadDigitCodes[] = {90, 87, 88, 89, 83, 84, 85, 79, 80, 81};
}  // namespace linux_xkb

int GetLinuxAlphanumericXkbKeyCode(int key_code) {
  if (key_code >= 'A' && key_code <= 'Z')
    return linux_xkb::kLetterCodes[key_code - 'A'];
  if (key_code >= '1' && key_code <= '9')
    return linux_xkb::kDigit1 + key_code - '1';
  if (key_code == '0')
    return linux_xkb::kDigit0;
  return linux_xkb::kUnknown;
}

int GetLinuxPrintableXkbKeyCode(int key_code) {
  const int alphanumeric_key_code = GetLinuxAlphanumericXkbKeyCode(key_code);
  if (alphanumeric_key_code != linux_xkb::kUnknown)
    return alphanumeric_key_code;
  switch (key_code) {
    case ' ':
      return linux_xkb::kSpace;
    case '-':
      return linux_xkb::kMinus;
    case '=':
      return linux_xkb::kEqual;
    case '[':
      return linux_xkb::kBracketLeft;
    case ']':
      return linux_xkb::kBracketRight;
    case '\\':
      return linux_xkb::kBackslash;
    case ';':
      return linux_xkb::kSemicolon;
    case '\'':
      return linux_xkb::kQuote;
    case '`':
      return linux_xkb::kBackquote;
    case ',':
      return 59;
    case '.':
      return 60;
    case '/':
      return 61;
    default:
      return linux_xkb::kUnknown;
  }
}

int GetLinuxFunctionXkbKeyCode(int function_number) {
  if (function_number >= 1 && function_number <= 10)
    return linux_xkb::kF1 + function_number - 1;
  if (function_number >= 11 && function_number <= 12)
    return linux_xkb::kF11 + function_number - 11;
  if (function_number >= 13 && function_number <= 24)
    return linux_xkb::kF13 + function_number - 13;
  return linux_xkb::kUnknown;
}

int GetLinuxNumpadDigitXkbKeyCode(int digit) {
  if (digit < 0 || digit > 9)
    return linux_xkb::kUnknown;
  return linux_xkb::kNumpadDigitCodes[digit];
}

int GetAwtLinuxXkbKeyCode(int key_code, int key_location) {
  if (key_location == awt::kKeyLocationNumpad) {
    switch (key_code) {
      case awt::kVkClear:
        return GetLinuxNumpadDigitXkbKeyCode(5);
      case awt::kVkPageUp:
        return GetLinuxNumpadDigitXkbKeyCode(9);
      case awt::kVkPageDown:
        return GetLinuxNumpadDigitXkbKeyCode(3);
      case awt::kVkEnd:
        return GetLinuxNumpadDigitXkbKeyCode(1);
      case awt::kVkHome:
        return GetLinuxNumpadDigitXkbKeyCode(7);
      case awt::kVkLeft:
        return GetLinuxNumpadDigitXkbKeyCode(4);
      case awt::kVkUp:
        return GetLinuxNumpadDigitXkbKeyCode(8);
      case awt::kVkRight:
        return GetLinuxNumpadDigitXkbKeyCode(6);
      case awt::kVkDown:
        return GetLinuxNumpadDigitXkbKeyCode(2);
      case awt::kVkDelete:
        return linux_xkb::kNumpadDecimal;
      case awt::kVkInsert:
        return GetLinuxNumpadDigitXkbKeyCode(0);
      case '=':
        return linux_xkb::kNumpadEqual;
      case awt::kVkTab:
      case awt::kVkSpace:
        // Chromium has no DOM physical code for the uncommon keypad Tab and
        // Space positions exposed by some X11 keyboard maps.
        return linux_xkb::kUnknown;
      default:
        break;
    }
  }

  const int alphanumeric_key_code = GetLinuxAlphanumericXkbKeyCode(key_code);
  if (alphanumeric_key_code != linux_xkb::kUnknown)
    return alphanumeric_key_code;
  if (key_code >= awt::kVkF1 && key_code <= awt::kVkF12)
    return GetLinuxFunctionXkbKeyCode(key_code - awt::kVkF1 + 1);
  if (key_code >= awt::kVkF13 && key_code <= awt::kVkF24)
    return GetLinuxFunctionXkbKeyCode(key_code - awt::kVkF13 + 13);
  if (key_code >= awt::kVkNumpad0 && key_code <= awt::kVkNumpad9)
    return GetLinuxNumpadDigitXkbKeyCode(key_code - awt::kVkNumpad0);
  if (key_code >= awt::kVkDeadGrave && key_code <= awt::kVkDeadSemivoicedSound)
    return linux_xkb::kUnknown;

  switch (key_code) {
    case awt::kVkBackSpace:
      return linux_xkb::kBackspace;
    case awt::kVkTab:
      return linux_xkb::kTab;
    case awt::kVkEnter:
      return key_location == awt::kKeyLocationNumpad ? linux_xkb::kNumpadEnter
                                                     : linux_xkb::kEnter;
    case awt::kVkShift:
      return key_location == awt::kKeyLocationRight ? linux_xkb::kShiftRight
                                                    : linux_xkb::kShiftLeft;
    case awt::kVkControl:
      return key_location == awt::kKeyLocationRight ? linux_xkb::kControlRight
                                                    : linux_xkb::kControlLeft;
    case awt::kVkAlt:
      return key_location == awt::kKeyLocationRight ? linux_xkb::kAltRight
                                                    : linux_xkb::kAltLeft;
    case awt::kVkPause:
      return linux_xkb::kPause;
    case awt::kVkCapsLock:
      return linux_xkb::kCapsLock;
    case awt::kVkEscape:
      return linux_xkb::kEscape;
    case awt::kVkSpace:
      return linux_xkb::kSpace;
    case ',':
      return 59;
    case '-':
      return linux_xkb::kMinus;
    case '.':
      return 60;
    case '/':
      return 61;
    case ';':
      return linux_xkb::kSemicolon;
    case '=':
      return linux_xkb::kEqual;
    case '[':
      return linux_xkb::kBracketLeft;
    case '\\':
      return linux_xkb::kBackslash;
    case ']':
      return linux_xkb::kBracketRight;
    case awt::kVkBackQuote:
      return linux_xkb::kBackquote;
    case awt::kVkQuote:
      return linux_xkb::kQuote;
    case awt::kVkPageUp:
      return linux_xkb::kPageUp;
    case awt::kVkPageDown:
      return linux_xkb::kPageDown;
    case awt::kVkEnd:
      return linux_xkb::kEnd;
    case awt::kVkHome:
      return linux_xkb::kHome;
    case awt::kVkLeft:
      return linux_xkb::kArrowLeft;
    case awt::kVkUp:
      return linux_xkb::kArrowUp;
    case awt::kVkRight:
      return linux_xkb::kArrowRight;
    case awt::kVkDown:
      return linux_xkb::kArrowDown;
    case awt::kVkMultiply:
      return linux_xkb::kNumpadMultiply;
    case awt::kVkAdd:
      return linux_xkb::kNumpadAdd;
    case awt::kVkSeparator:
      return linux_xkb::kNumpadComma;
    case awt::kVkSubtract:
      return linux_xkb::kNumpadSubtract;
    case awt::kVkDecimal:
      return linux_xkb::kNumpadDecimal;
    case awt::kVkDivide:
      return linux_xkb::kNumpadDivide;
    case awt::kVkDelete:
      return linux_xkb::kDelete;
    case awt::kVkNumLock:
      return linux_xkb::kNumLock;
    case awt::kVkScrollLock:
      return linux_xkb::kScrollLock;
    case awt::kVkPrintScreen:
      return linux_xkb::kPrintScreen;
    case awt::kVkInsert:
      return linux_xkb::kInsert;
    case awt::kVkHelp:
      return linux_xkb::kHelp;
    case awt::kVkMeta:
    case awt::kVkWindows:
      return key_location == awt::kKeyLocationRight ? linux_xkb::kMetaRight
                                                    : linux_xkb::kMetaLeft;
    case awt::kVkKpUp:
      return GetLinuxNumpadDigitXkbKeyCode(8);
    case awt::kVkKpDown:
      return GetLinuxNumpadDigitXkbKeyCode(2);
    case awt::kVkKpLeft:
      return GetLinuxNumpadDigitXkbKeyCode(4);
    case awt::kVkKpRight:
      return GetLinuxNumpadDigitXkbKeyCode(6);
    case awt::kVkContextMenu:
      return linux_xkb::kContextMenu;
    case awt::kVkAltGraph:
      return linux_xkb::kAltRight;
    case awt::kVkCancel:
    case awt::kVkClear:
    case awt::kVkCompose:
    default:
      // Cancel, Clear, Compose, dead keys and unknown logical keys do not
      // identify a canonical physical position in Chromium's table.
      return linux_xkb::kUnknown;
  }
}

int GetGlfwLinuxXkbKeyCode(int key_code) {
  const int printable_key_code = GetLinuxPrintableXkbKeyCode(key_code);
  if (printable_key_code != linux_xkb::kUnknown)
    return printable_key_code;
  if (key_code >= glfw::kKeyF1 && key_code <= glfw::kKeyF25)
    return GetLinuxFunctionXkbKeyCode(key_code - glfw::kKeyF1 + 1);
  if (key_code >= glfw::kKeyKp0 && key_code <= glfw::kKeyKp9)
    return GetLinuxNumpadDigitXkbKeyCode(key_code - glfw::kKeyKp0);

  switch (key_code) {
    case glfw::kKeyEscape:
      return linux_xkb::kEscape;
    case glfw::kKeyEnter:
      return linux_xkb::kEnter;
    case glfw::kKeyTab:
      return linux_xkb::kTab;
    case glfw::kKeyBackspace:
      return linux_xkb::kBackspace;
    case glfw::kKeyInsert:
      return linux_xkb::kInsert;
    case glfw::kKeyDelete:
      return linux_xkb::kDelete;
    case glfw::kKeyRight:
      return linux_xkb::kArrowRight;
    case glfw::kKeyLeft:
      return linux_xkb::kArrowLeft;
    case glfw::kKeyDown:
      return linux_xkb::kArrowDown;
    case glfw::kKeyUp:
      return linux_xkb::kArrowUp;
    case glfw::kKeyPageUp:
      return linux_xkb::kPageUp;
    case glfw::kKeyPageDown:
      return linux_xkb::kPageDown;
    case glfw::kKeyHome:
      return linux_xkb::kHome;
    case glfw::kKeyEnd:
      return linux_xkb::kEnd;
    case glfw::kKeyCapsLock:
      return linux_xkb::kCapsLock;
    case glfw::kKeyScrollLock:
      return linux_xkb::kScrollLock;
    case glfw::kKeyNumLock:
      return linux_xkb::kNumLock;
    case glfw::kKeyPrintScreen:
      return linux_xkb::kPrintScreen;
    case glfw::kKeyPause:
      return linux_xkb::kPause;
    case glfw::kKeyKpDecimal:
      return linux_xkb::kNumpadDecimal;
    case glfw::kKeyKpDivide:
      return linux_xkb::kNumpadDivide;
    case glfw::kKeyKpMultiply:
      return linux_xkb::kNumpadMultiply;
    case glfw::kKeyKpSubtract:
      return linux_xkb::kNumpadSubtract;
    case glfw::kKeyKpAdd:
      return linux_xkb::kNumpadAdd;
    case glfw::kKeyKpEnter:
      return linux_xkb::kNumpadEnter;
    case glfw::kKeyKpEqual:
      return linux_xkb::kNumpadEqual;
    case glfw::kKeyLeftShift:
      return linux_xkb::kShiftLeft;
    case glfw::kKeyLeftControl:
      return linux_xkb::kControlLeft;
    case glfw::kKeyLeftAlt:
      return linux_xkb::kAltLeft;
    case glfw::kKeyLeftSuper:
      return linux_xkb::kMetaLeft;
    case glfw::kKeyRightShift:
      return linux_xkb::kShiftRight;
    case glfw::kKeyRightControl:
      return linux_xkb::kControlRight;
    case glfw::kKeyRightAlt:
      return linux_xkb::kAltRight;
    case glfw::kKeyRightSuper:
      return linux_xkb::kMetaRight;
    case glfw::kKeyMenu:
      return linux_xkb::kContextMenu;
    default:
      // GLFW world keys, F25 and unknown values have no corresponding
      // Chromium XKB physical code.
      return linux_xkb::kUnknown;
  }
}

int GetLinuxXkbKeyCodeFallback(int key_code, int key_location, bool typed, InputEventSemantics semantics) {
  // Typed events carry text, not a physical key identity. A positive supplied
  // raw code still wins in ResolveLinuxNativeKeyCode below.
  if (typed)
    return linux_xkb::kUnknown;
  return semantics == InputEventSemantics::kAwt
             ? GetAwtLinuxXkbKeyCode(key_code, key_location)
             : GetGlfwLinuxXkbKeyCode(key_code);
}

bool IsBoundedPositiveCode(std::int64_t code) {
  return code > 0 && code <= std::numeric_limits<int>::max();
}

}  // namespace

int ResolveLinuxNativeKeyCode(std::int64_t supplied_native_key_code, int key_code, int key_location, bool typed, InputEventSemantics semantics) {
  if (IsBoundedPositiveCode(supplied_native_key_code))
    return static_cast<int>(supplied_native_key_code);
  return GetLinuxXkbKeyCodeFallback(key_code, key_location, typed, semantics);
}

int ResolveWindowsNativeKeyCode(std::int64_t supplied_scan_code, std::uint32_t mapped_scan_code, bool extended) {
  const bool has_supplied_scan_code = IsBoundedPositiveCode(supplied_scan_code);
  std::uint32_t scan_code = has_supplied_scan_code
                                ? static_cast<std::uint32_t>(supplied_scan_code)
                                : mapped_scan_code;
  if (scan_code == 0 ||
      scan_code > static_cast<std::uint32_t>(std::numeric_limits<int>::max()))
    return 0;

  // GLFW represents an E0-prefixed Windows scan code as 0x100 | scan byte.
  // Chromium's table uses the OEM form 0xE000 | scan byte.
  if ((scan_code & 0xFF00U) == 0x0100U)
    scan_code = 0xE000U | (scan_code & 0xFFU);
  else if (scan_code <= 0xFFU && extended)
    scan_code = 0xE000U | scan_code;
  else if (!has_supplied_scan_code && !extended &&
           ((scan_code & 0xFF00U) == 0xE000U ||
            (scan_code & 0xFF00U) == 0xE100U))
    scan_code &= 0xFFU;
  return static_cast<int>(scan_code);
}

}  // namespace key_event_platform_util
