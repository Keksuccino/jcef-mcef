// Copyright (c) 2013 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "CefBrowser_N.h"

#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <string_view>

#include "include/base/cef_callback.h"
#include "include/cef_browser.h"
#include "include/cef_parser.h"
#include "include/cef_task.h"
#include "include/wrapper/cef_closure_task.h"

#include "browser_process_handler.h"
#include "browser_settings.h"
#include "client_handler.h"
#include "devtools_message_observer.h"
#include "double_callback.h"
#include "int_callback.h"
#include "jni_util.h"
#include "key_event_platform_util.h"
#include "life_span_handler.h"
#include "mouse_wheel_platform_util.h"
#include "pdf_print_callback.h"
#include "render_handler.h"
#include "run_file_dialog_callback.h"
#include "string_visitor.h"
#include "temp_window.h"
#include "window_handler.h"

#if defined(OS_LINUX)
#define XK_3270  // for XK_3270_BackTab
#include <X11/X.h>
#include <X11/XF86keysym.h>
#include <X11/keysym.h>

#include "include/cef_version.h"

// CEF 151's SUPPORTS_OZONE_X11 path reconstructs DomKey from the Windows key
// code and Shift state. It ignores CefKeyEvent.character and Caps Lock for
// that calculation, so the Linux live-input expectations deliberately expose
// this upstream limitation while independently checking JCEF's character
// mapping. Force every CEF upgrade to re-audit both sides of that contract.
static_assert(std::string_view(CEF_VERSION) == "151.2.3+g89cd581+chromium-151.0.7922.34" && CEF_COMMIT_NUMBER == 3553 && std::string_view(CEF_COMMIT_HASH) == "89cd5813e47d84c68e56ced336c2c01b7dc77b8d" && CHROME_VERSION_MAJOR == 151 && CHROME_VERSION_MINOR == 0 && CHROME_VERSION_BUILD == 7922 && CHROME_VERSION_PATCH == 34, "CEF changed: re-audit CefBrowserPlatformDelegateNativeLinux::TranslateUiKeyEvent, XKeysymForWindowsKeyCode and the Linux OSR live-key expectations");
#endif

#if defined(OS_MACOSX)
#include <Carbon/Carbon.h>
#include "util_mac.h"
#endif

#if defined(OS_WIN)
#undef MOUSE_MOVED
#endif

namespace {

static_assert(PET_VIEW == 0, "CEF API 15100 PET_VIEW value changed");
static_assert(PET_POPUP == 1, "CEF API 15100 PET_POPUP value changed");
static_assert(CEF_ZOOM_COMMAND_OUT == 0, "CEF API 15100 CEF_ZOOM_COMMAND_OUT value changed");
static_assert(CEF_ZOOM_COMMAND_RESET == 1, "CEF API 15100 CEF_ZOOM_COMMAND_RESET value changed");
static_assert(CEF_ZOOM_COMMAND_IN == 2, "CEF API 15100 CEF_ZOOM_COMMAND_IN value changed");

bool GetPaintElementType(JNIEnv* env, jint value, CefBrowserHost::PaintElementType* type) {
  switch (value) {
    case PET_VIEW:
      *type = PET_VIEW;
      return true;
    case PET_POPUP:
      *type = PET_POPUP;
      return true;
    default:
      ScopedJNIClass exception_class(env, "java/lang/IllegalArgumentException");
      if (exception_class)
        env->ThrowNew(exception_class, "Unknown CEF paint element type");
      return false;
  }
}

bool GetZoomCommand(JNIEnv* env, jint value, cef_zoom_command_t* command) {
  switch (value) {
    case CEF_ZOOM_COMMAND_OUT:
      *command = CEF_ZOOM_COMMAND_OUT;
      return true;
    case CEF_ZOOM_COMMAND_RESET:
      *command = CEF_ZOOM_COMMAND_RESET;
      return true;
    case CEF_ZOOM_COMMAND_IN:
      *command = CEF_ZOOM_COMMAND_IN;
      return true;
    default:
      ScopedJNIClass exception_class(env, "java/lang/IllegalArgumentException");
      if (exception_class)
        env->ThrowNew(exception_class, "Unknown CEF zoom command");
      return false;
  }
}

// These values are stable public ABI constants from java.awt.event and GLFW.
// Keeping the input domains explicit avoids a runtime dependency on LWJGL for
// both Swing and MCEF while preserving the legacy DTO wire format.
namespace awt {
constexpr int kShiftDownMask = 1 << 6;
constexpr int kControlDownMask = 1 << 7;
constexpr int kMetaDownMask = 1 << 8;
constexpr int kAltDownMask = 1 << 9;
constexpr int kButton1DownMask = 1 << 10;
constexpr int kButton2DownMask = 1 << 11;
constexpr int kButton3DownMask = 1 << 12;
constexpr int kAltGraphDownMask = 1 << 13;

constexpr int kKeyTyped = 400;
constexpr int kKeyPressed = 401;
constexpr int kKeyReleased = 402;
constexpr char16_t kCharUndefined = 0xFFFF;
constexpr int kKeyLocationUnknown = 0;
constexpr int kKeyLocationStandard = 1;
constexpr int kKeyLocationLeft = 2;
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
constexpr int kVkDeadAcute = 129;
constexpr int kVkDeadCircumflex = 130;
constexpr int kVkDeadTilde = 131;
constexpr int kVkDeadMacron = 132;
constexpr int kVkDeadBreve = 133;
constexpr int kVkDeadAboveDot = 134;
constexpr int kVkDeadDiaeresis = 135;
constexpr int kVkDeadAboveRing = 136;
constexpr int kVkDeadDoubleAcute = 137;
constexpr int kVkDeadCaron = 138;
constexpr int kVkDeadCedilla = 139;
constexpr int kVkDeadOgonek = 140;
constexpr int kVkDeadIota = 141;
constexpr int kVkDeadVoicedSound = 142;
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

constexpr int kMousePressed = 501;
constexpr int kMouseReleased = 502;
constexpr int kMouseMoved = 503;
constexpr int kMouseEntered = 504;
constexpr int kMouseExited = 505;
constexpr int kMouseDragged = 506;
constexpr int kButton1 = 1;
constexpr int kButton2 = 2;
constexpr int kButton3 = 3;
constexpr int kWheelUnitScroll = 0;
constexpr int kWheelBlockScroll = 1;
}  // namespace awt

namespace glfw {
constexpr int kRelease = 0;
constexpr int kPress = 1;
// CefKeyEvent predates explicit repeat support and reserves action 2 for typed
// text.
constexpr int kTyped = 2;
constexpr int kExplicitRepeat = 3;
constexpr int kModShift = 0x0001;
constexpr int kModControl = 0x0002;
constexpr int kModAlt = 0x0004;
constexpr int kModSuper = 0x0008;
constexpr int kModCapsLock = 0x0010;
constexpr int kModNumLock = 0x0020;
constexpr int kLegacyButton1Mask = 0x10;
constexpr int kLegacyButton2Mask = 0x20;
constexpr int kLegacyButton3Mask = 0x40;

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

// CefMouseEvent exposes normalized logical buttons in this historical fork.
constexpr int kMouseButton1 = 0;
constexpr int kMouseButton2 = 1;
constexpr int kMouseButton3 = 2;
constexpr int kMouseMoved = 503;
constexpr int kMouseEntered = 504;
constexpr int kMouseExited = 505;
constexpr int kMouseDragged = 506;
constexpr int kWheelUnitScroll = 0;
}  // namespace glfw

static_assert(EVENTFLAG_ALTGR_DOWN == (1 << 12));
static_assert(EVENTFLAG_IS_REPEAT == (1 << 13));
static_assert(EVENTFLAG_PRECISION_SCROLLING_DELTA == (1 << 14));
static_assert(EVENTFLAG_SCROLL_BY_PAGE == (1 << 15));

using key_event_platform_util::InputEventSemantics;
#if defined(OS_WIN)
using mouse_wheel_platform_util::WindowsCefWheelDelta;
using mouse_wheel_platform_util::WindowsCefWheelDeltaStatus;
#endif

int GetCefModifiersAwt(int modifiers) {
  int cef_modifiers = 0;
  if (modifiers & awt::kAltDownMask)
    cef_modifiers |= EVENTFLAG_ALT_DOWN;
  if (modifiers & awt::kButton1DownMask)
    cef_modifiers |= EVENTFLAG_LEFT_MOUSE_BUTTON;
  if (modifiers & awt::kButton2DownMask)
    cef_modifiers |= EVENTFLAG_MIDDLE_MOUSE_BUTTON;
  if (modifiers & awt::kButton3DownMask)
    cef_modifiers |= EVENTFLAG_RIGHT_MOUSE_BUTTON;
  if (modifiers & awt::kControlDownMask)
    cef_modifiers |= EVENTFLAG_CONTROL_DOWN;
  if (modifiers & awt::kMetaDownMask)
    cef_modifiers |= EVENTFLAG_COMMAND_DOWN;
  if (modifiers & awt::kShiftDownMask)
    cef_modifiers |= EVENTFLAG_SHIFT_DOWN;
  if (modifiers & awt::kAltGraphDownMask)
    cef_modifiers |= EVENTFLAG_ALTGR_DOWN;
  return cef_modifiers;
}

int GetCefKeyModifiersGlfw(int modifiers) {
  int cef_modifiers = 0;
  if (modifiers & glfw::kModAlt)
    cef_modifiers |= EVENTFLAG_ALT_DOWN;
  if (modifiers & glfw::kModControl)
    cef_modifiers |= EVENTFLAG_CONTROL_DOWN;
  if (modifiers & glfw::kModSuper)
    cef_modifiers |= EVENTFLAG_COMMAND_DOWN;
  if (modifiers & glfw::kModShift)
    cef_modifiers |= EVENTFLAG_SHIFT_DOWN;
  if (modifiers & glfw::kModCapsLock)
    cef_modifiers |= EVENTFLAG_CAPS_LOCK_ON;
  if (modifiers & glfw::kModNumLock)
    cef_modifiers |= EVENTFLAG_NUM_LOCK_ON;
  return cef_modifiers;
}

int GetCefPointerModifiersGlfw(int modifiers) {
  int cef_modifiers = GetCefKeyModifiersGlfw(modifiers & ~(glfw::kModCapsLock | glfw::kModNumLock));
  if (modifiers & glfw::kLegacyButton1Mask)
    cef_modifiers |= EVENTFLAG_LEFT_MOUSE_BUTTON;
  if (modifiers & glfw::kLegacyButton2Mask)
    cef_modifiers |= EVENTFLAG_MIDDLE_MOUSE_BUTTON;
  if (modifiers & glfw::kLegacyButton3Mask)
    cef_modifiers |= EVENTFLAG_RIGHT_MOUSE_BUTTON;
  return cef_modifiers;
}

int GetCefKeyModifiers(int modifiers, InputEventSemantics semantics) {
  return semantics == InputEventSemantics::kAwt
             ? GetCefModifiersAwt(modifiers)
             : GetCefKeyModifiersGlfw(modifiers);
}

int GetCefPointerModifiers(int modifiers, InputEventSemantics semantics) {
  return semantics == InputEventSemantics::kAwt
             ? GetCefModifiersAwt(modifiers)
             : GetCefPointerModifiersGlfw(modifiers);
}

bool HasDefinedKeyChar(char16_t key_char) {
  return key_char != awt::kCharUndefined;
}

bool IsValidBmpCharacter(jlong character) {
  return character > 0 && character < awt::kCharUndefined &&
         !(character >= 0xD800 && character <= 0xDFFF);
}

bool IsPrintableKeyChar(char16_t character) {
  return HasDefinedKeyChar(character) && character >= 0x20 && character != 0x7F;
}

char16_t GetShiftedDigitCharacter(int key_code) {
  constexpr char kShiftedDigits[] = ")!@#$%^&*(";
  return kShiftedDigits[key_code - '0'];
}

bool IsGlfwStandardPrintableKey(int key_code) {
  if ((key_code >= '0' && key_code <= '9') ||
      (key_code >= 'A' && key_code <= 'Z'))
    return true;
  switch (key_code) {
    case 32:  // GLFW_KEY_SPACE
    case 39:  // GLFW_KEY_APOSTROPHE
    case 44:  // GLFW_KEY_COMMA
    case 45:  // GLFW_KEY_MINUS
    case 46:  // GLFW_KEY_PERIOD
    case 47:  // GLFW_KEY_SLASH
    case 59:  // GLFW_KEY_SEMICOLON
    case 61:  // GLFW_KEY_EQUAL
    case 91:  // GLFW_KEY_LEFT_BRACKET
    case 92:  // GLFW_KEY_BACKSLASH
    case 93:  // GLFW_KEY_RIGHT_BRACKET
    case 96:  // GLFW_KEY_GRAVE_ACCENT
      return true;
    default:
      // GLFW_KEY_WORLD_1/2 are intentionally excluded. Their numeric identities
      // are not Unicode characters, even though the historical MCEF DTO casts
      // keyCode into keyChar.
      return false;
  }
}

char16_t GetGlfwRawUnmodifiedCharacter(int key_code, char16_t key_char, bool shift) {
  if (key_code >= 'A' && key_code <= 'Z' &&
      (!HasDefinedKeyChar(key_char) ||
       key_char == static_cast<char16_t>(key_code)))
    return static_cast<char16_t>(shift ? key_code : key_code + ('a' - 'A'));
  if (IsGlfwStandardPrintableKey(key_code) && HasDefinedKeyChar(key_char)) {
    if (!shift || key_char != static_cast<char16_t>(key_code))
      return key_char;
    if (key_code >= '0' && key_code <= '9')
      return GetShiftedDigitCharacter(key_code);
    switch (key_code) {
      case 39:
        return '"';
      case 44:
        return '<';
      case 45:
        return '_';
      case 46:
        return '>';
      case 47:
        return '?';
      case 59:
        return ':';
      case 61:
        return '+';
      case 91:
        return '{';
      case 92:
        return '|';
      case 93:
        return '}';
      case 96:
        return '~';
      default:
        return key_char;
    }
  }
  if (key_code >= glfw::kKeyKp0 && key_code <= glfw::kKeyKp9)
    return static_cast<char16_t>('0' + key_code - glfw::kKeyKp0);
  switch (key_code) {
    case glfw::kKeyKpDecimal:
      return '.';
    case glfw::kKeyKpDivide:
      return '/';
    case glfw::kKeyKpMultiply:
      return '*';
    case glfw::kKeyKpSubtract:
      return '-';
    case glfw::kKeyKpAdd:
      return '+';
    case glfw::kKeyKpEqual:
      return '=';
    case glfw::kKeyKpEnter:
    case glfw::kKeyEnter:
      return '\r';
    case glfw::kKeyBackspace:
      return '\b';
    case glfw::kKeyTab:
      return '\t';
    case glfw::kKeyEscape:
      return 0x1B;
    case glfw::kKeyDelete:
      return 0x7F;
    default:
      return awt::kCharUndefined;
  }
}

char16_t GetGlfwRawCharacter(int key_code, char16_t key_char, bool shift, bool caps_lock) {
  if (key_code >= 'A' && key_code <= 'Z' &&
      (!HasDefinedKeyChar(key_char) ||
       key_char == static_cast<char16_t>(key_code)))
    return static_cast<char16_t>(shift != caps_lock ? key_code
                                                    : key_code + ('a' - 'A'));
  return GetGlfwRawUnmodifiedCharacter(key_code, key_char, shift);
}

char16_t GetAwtPhysicalUnmodifiedCharacter(int key_code, bool shift) {
  if (key_code >= 'A' && key_code <= 'Z')
    return static_cast<char16_t>(shift ? key_code : key_code + ('a' - 'A'));
  if (key_code >= '0' && key_code <= '9')
    return shift ? GetShiftedDigitCharacter(key_code)
                 : static_cast<char16_t>(key_code);
  if (key_code >= awt::kVkNumpad0 && key_code <= awt::kVkNumpad9)
    return static_cast<char16_t>('0' + key_code - awt::kVkNumpad0);
  switch (key_code) {
    case awt::kVkSpace:
      return ' ';
    case 44:  // KeyEvent.VK_COMMA
      return shift ? '<' : ',';
    case 45:  // KeyEvent.VK_MINUS
      return shift ? '_' : '-';
    case 46:  // KeyEvent.VK_PERIOD
      return shift ? '>' : '.';
    case 47:  // KeyEvent.VK_SLASH
      return shift ? '?' : '/';
    case 59:  // KeyEvent.VK_SEMICOLON
      return shift ? ':' : ';';
    case 61:  // KeyEvent.VK_EQUALS
      return shift ? '+' : '=';
    case 91:  // KeyEvent.VK_OPEN_BRACKET
      return shift ? '{' : '[';
    case 92:  // KeyEvent.VK_BACK_SLASH
      return shift ? '|' : '\\';
    case 93:  // KeyEvent.VK_CLOSE_BRACKET
      return shift ? '}' : ']';
    case awt::kVkBackQuote:
      return shift ? '~' : '`';
    case awt::kVkQuote:
      return shift ? '"' : '\'';
    case awt::kVkDecimal:
      return '.';
    case awt::kVkDivide:
      return '/';
    case awt::kVkMultiply:
      return '*';
    case awt::kVkSubtract:
      return '-';
    case awt::kVkAdd:
      return '+';
    case awt::kVkSeparator:
      return ',';
    case awt::kVkEnter:
      return '\r';
    case awt::kVkBackSpace:
      return '\b';
    case awt::kVkTab:
      return '\t';
    case awt::kVkEscape:
      return 0x1B;
    case awt::kVkDelete:
      return 0x7F;
    default:
      return awt::kCharUndefined;
  }
}

char16_t GetAwtUnmodifiedCharacter(JNIEnv* env, char16_t key_char, int modifiers, jlong primary_level_unicode, char16_t physical_fallback) {
  constexpr int kCharacterModifiers = awt::kControlDownMask |
                                      awt::kAltDownMask | awt::kMetaDownMask |
                                      awt::kAltGraphDownMask;
  constexpr int kAlternateLevelModifiers =
      awt::kAltDownMask | awt::kAltGraphDownMask;
  const bool shift = (modifiers & awt::kShiftDownMask) != 0;
  const bool alternate_level = (modifiers & kAlternateLevelModifiers) != 0;
  const bool primary_valid = IsValidBmpCharacter(primary_level_unicode);
  const char16_t primary_character =
      primary_valid ? static_cast<char16_t>(primary_level_unicode)
                    : awt::kCharUndefined;
  if ((modifiers & kCharacterModifiers) == 0 && HasDefinedKeyChar(key_char))
    return key_char;
  // X11's private primary-level field removes Shift while selecting the keysym,
  // but can retain Alt/AltGr level selection. Never treat that
  // modifier-transformed value as unmodified text.
  if (primary_valid && !alternate_level && !shift)
    return primary_character;
  if (!alternate_level && IsPrintableKeyChar(key_char))
    return key_char;
  if (primary_valid && !alternate_level) {
    ScopedJNIClass character_class(env, "java/lang/Character");
    int uppercase_character = primary_character;
    if (character_class &&
        CallStaticJNIMethodII_V(env, character_class, "toUpperCase", &uppercase_character, primary_character) &&
        IsValidBmpCharacter(uppercase_character) &&
        uppercase_character != primary_character)
      return static_cast<char16_t>(uppercase_character);
  }
  if (HasDefinedKeyChar(physical_fallback))
    return physical_fallback;
  return alternate_level ? awt::kCharUndefined : primary_character;
}

int GetKeyIdentityModifiers(int key_code, int key_location, InputEventSemantics semantics) {
  if (semantics == InputEventSemantics::kAwt) {
    switch (key_location) {
      case awt::kKeyLocationLeft:
        return EVENTFLAG_IS_LEFT;
      case awt::kKeyLocationRight:
        return EVENTFLAG_IS_RIGHT;
      case awt::kKeyLocationNumpad:
        return EVENTFLAG_IS_KEY_PAD;
      case awt::kKeyLocationUnknown:
      case awt::kKeyLocationStandard:
      default:
        return 0;
    }
  }

  if (key_code >= glfw::kKeyKp0 && key_code <= glfw::kKeyKpEqual)
    return EVENTFLAG_IS_KEY_PAD;
  switch (key_code) {
    case glfw::kKeyLeftShift:
    case glfw::kKeyLeftControl:
    case glfw::kKeyLeftAlt:
    case glfw::kKeyLeftSuper:
      return EVENTFLAG_IS_LEFT;
    case glfw::kKeyRightShift:
    case glfw::kKeyRightControl:
    case glfw::kKeyRightAlt:
    case glfw::kKeyRightSuper:
      return EVENTFLAG_IS_RIGHT;
    default:
      return 0;
  }
}

#if defined(OS_WIN)
const char* GetWindowsCefWheelDeltaIssueReason(WindowsCefWheelDeltaStatus status) {
  switch (status) {
    case WindowsCefWheelDeltaStatus::kApproximated:
      return "CEF 151's integer Windows scaling skips the exact magnitude";
    case WindowsCefWheelDeltaStatus::kScrollingDisabled:
      return "the Windows line/character scroll setting is zero";
    case WindowsCefWheelDeltaStatus::kPageScrolling:
      return "CEF 151 scales the delta by WHEEL_PAGESCROLL before applying "
             "page granularity";
    case WindowsCefWheelDeltaStatus::kSuccess:
      return "success";
  }
  return "an unknown conversion error occurred";
}

void LogWindowsCefWheelDeltaIssueOnce(WindowsCefWheelDeltaStatus status, int target_delta, int translated_delta, bool horizontal) {
  static std::atomic_uint logged_statuses{0};
  const unsigned int status_bit = 1U << static_cast<unsigned int>(status);
  if (logged_statuses.fetch_or(status_bit, std::memory_order_relaxed) & status_bit)
    return;
  if (status == WindowsCefWheelDeltaStatus::kApproximated) {
    LOG(WARNING) << "Approximating AWT "
                 << (horizontal ? "horizontal" : "vertical")
                 << " wheel translated delta " << target_delta << " as "
                 << translated_delta << " because "
                 << GetWindowsCefWheelDeltaIssueReason(status) << ".";
    return;
  }
  LOG(WARNING) << "Ignoring AWT " << (horizontal ? "horizontal" : "vertical")
               << " wheel event with translated delta " << target_delta
               << " because " << GetWindowsCefWheelDeltaIssueReason(status)
               << ".";
}

void SendWindowsAwtMouseWheelEvent(CefRefPtr<CefBrowser> browser, CefMouseEvent event, int target_delta, bool horizontal) {
  if (!CefCurrentlyOn(TID_UI)) {
    CefPostTask(TID_UI, base::BindOnce(&SendWindowsAwtMouseWheelEvent, browser, event, target_delta, horizontal));
    return;
  }

  // CefBrowserHostBase normally posts this call to the CEF UI thread before
  // its Windows delegate reads the scroll setting. Invert on that same thread
  // so our SystemParametersInfo query immediately precedes CEF's query and the
  // event remains ordered with other posted browser input.
  if (!browser.get() || !browser->IsValid())
    return;
  CefRefPtr<CefBrowserHost> host = browser->GetHost();
  if (!host)
    return;
  const WindowsCefWheelDelta converted_delta = mouse_wheel_platform_util::GetWindowsCefWheelDelta(target_delta, horizontal);
  if (converted_delta.status != WindowsCefWheelDeltaStatus::kSuccess &&
      converted_delta.status != WindowsCefWheelDeltaStatus::kApproximated) {
    LogWindowsCefWheelDeltaIssueOnce(converted_delta.status, target_delta, converted_delta.translated_delta, horizontal);
    return;
  }
  if (converted_delta.status == WindowsCefWheelDeltaStatus::kApproximated)
    LogWindowsCefWheelDeltaIssueOnce(converted_delta.status, target_delta, converted_delta.translated_delta, horizontal);
  const int delta_x = horizontal ? converted_delta.delta : 0;
  const int delta_y = horizontal ? 0 : converted_delta.delta;
  host->SendMouseWheelEvent(event, delta_x, delta_y);
}
#endif

// Windows virtual-key values are the cross-platform key identity used by CEF.
// Keeping control character synthesis in one place prevents the platform
// bridges from drifting on punctuation keys, where the incoming character may
// already have been transformed by the OS.
char16_t GetControlCharacterFromWindowsKeyCode(int windows_key_code, bool shift) {
  if (windows_key_code >= 0x41 && windows_key_code <= 0x5A)
    return static_cast<char16_t>(windows_key_code - 0x41 + 1);
  if (shift) {
    if (windows_key_code == 0x32)
      return 0;
    if (windows_key_code == 0x36)
      return 0x1E;
    if (windows_key_code == 0xBD)
      return 0x1F;
    return 0;
  }
  if (windows_key_code == 0xDB)
    return 0x1B;
  if (windows_key_code == 0xDC)
    return 0x1C;
  if (windows_key_code == 0xDD)
    return 0x1D;
  if (windows_key_code == 0x0D)
    return 0x0A;
  return 0;
}

#if defined(OS_LINUX)

// From ui/events/keycodes/keyboard_codes_posix.h.
enum KeyboardCode {
  VKEY_BACK = 0x08,
  VKEY_TAB = 0x09,
  VKEY_BACKTAB = 0x0A,
  VKEY_CLEAR = 0x0C,
  VKEY_RETURN = 0x0D,
  VKEY_SHIFT = 0x10,
  VKEY_CONTROL = 0x11,
  VKEY_MENU = 0x12,
  VKEY_PAUSE = 0x13,
  VKEY_CAPITAL = 0x14,
  VKEY_KANA = 0x15,
  VKEY_HANGUL = 0x15,
  VKEY_JUNJA = 0x17,
  VKEY_FINAL = 0x18,
  VKEY_HANJA = 0x19,
  VKEY_KANJI = 0x19,
  VKEY_ESCAPE = 0x1B,
  VKEY_CONVERT = 0x1C,
  VKEY_NONCONVERT = 0x1D,
  VKEY_ACCEPT = 0x1E,
  VKEY_MODECHANGE = 0x1F,
  VKEY_SPACE = 0x20,
  VKEY_PRIOR = 0x21,
  VKEY_NEXT = 0x22,
  VKEY_END = 0x23,
  VKEY_HOME = 0x24,
  VKEY_LEFT = 0x25,
  VKEY_UP = 0x26,
  VKEY_RIGHT = 0x27,
  VKEY_DOWN = 0x28,
  VKEY_SELECT = 0x29,
  VKEY_PRINT = 0x2A,
  VKEY_EXECUTE = 0x2B,
  VKEY_SNAPSHOT = 0x2C,
  VKEY_INSERT = 0x2D,
  VKEY_DELETE = 0x2E,
  VKEY_HELP = 0x2F,
  VKEY_0 = 0x30,
  VKEY_1 = 0x31,
  VKEY_2 = 0x32,
  VKEY_3 = 0x33,
  VKEY_4 = 0x34,
  VKEY_5 = 0x35,
  VKEY_6 = 0x36,
  VKEY_7 = 0x37,
  VKEY_8 = 0x38,
  VKEY_9 = 0x39,
  VKEY_A = 0x41,
  VKEY_B = 0x42,
  VKEY_C = 0x43,
  VKEY_D = 0x44,
  VKEY_E = 0x45,
  VKEY_F = 0x46,
  VKEY_G = 0x47,
  VKEY_H = 0x48,
  VKEY_I = 0x49,
  VKEY_J = 0x4A,
  VKEY_K = 0x4B,
  VKEY_L = 0x4C,
  VKEY_M = 0x4D,
  VKEY_N = 0x4E,
  VKEY_O = 0x4F,
  VKEY_P = 0x50,
  VKEY_Q = 0x51,
  VKEY_R = 0x52,
  VKEY_S = 0x53,
  VKEY_T = 0x54,
  VKEY_U = 0x55,
  VKEY_V = 0x56,
  VKEY_W = 0x57,
  VKEY_X = 0x58,
  VKEY_Y = 0x59,
  VKEY_Z = 0x5A,
  VKEY_LWIN = 0x5B,
  VKEY_COMMAND = VKEY_LWIN,  // Provide the Mac name for convenience.
  VKEY_RWIN = 0x5C,
  VKEY_APPS = 0x5D,
  VKEY_SLEEP = 0x5F,
  VKEY_NUMPAD0 = 0x60,
  VKEY_NUMPAD1 = 0x61,
  VKEY_NUMPAD2 = 0x62,
  VKEY_NUMPAD3 = 0x63,
  VKEY_NUMPAD4 = 0x64,
  VKEY_NUMPAD5 = 0x65,
  VKEY_NUMPAD6 = 0x66,
  VKEY_NUMPAD7 = 0x67,
  VKEY_NUMPAD8 = 0x68,
  VKEY_NUMPAD9 = 0x69,
  VKEY_MULTIPLY = 0x6A,
  VKEY_ADD = 0x6B,
  VKEY_SEPARATOR = 0x6C,
  VKEY_SUBTRACT = 0x6D,
  VKEY_DECIMAL = 0x6E,
  VKEY_DIVIDE = 0x6F,
  VKEY_F1 = 0x70,
  VKEY_F2 = 0x71,
  VKEY_F3 = 0x72,
  VKEY_F4 = 0x73,
  VKEY_F5 = 0x74,
  VKEY_F6 = 0x75,
  VKEY_F7 = 0x76,
  VKEY_F8 = 0x77,
  VKEY_F9 = 0x78,
  VKEY_F10 = 0x79,
  VKEY_F11 = 0x7A,
  VKEY_F12 = 0x7B,
  VKEY_F13 = 0x7C,
  VKEY_F14 = 0x7D,
  VKEY_F15 = 0x7E,
  VKEY_F16 = 0x7F,
  VKEY_F17 = 0x80,
  VKEY_F18 = 0x81,
  VKEY_F19 = 0x82,
  VKEY_F20 = 0x83,
  VKEY_F21 = 0x84,
  VKEY_F22 = 0x85,
  VKEY_F23 = 0x86,
  VKEY_F24 = 0x87,
  VKEY_NUMLOCK = 0x90,
  VKEY_SCROLL = 0x91,
  VKEY_LSHIFT = 0xA0,
  VKEY_RSHIFT = 0xA1,
  VKEY_LCONTROL = 0xA2,
  VKEY_RCONTROL = 0xA3,
  VKEY_LMENU = 0xA4,
  VKEY_RMENU = 0xA5,
  VKEY_BROWSER_BACK = 0xA6,
  VKEY_BROWSER_FORWARD = 0xA7,
  VKEY_BROWSER_REFRESH = 0xA8,
  VKEY_BROWSER_STOP = 0xA9,
  VKEY_BROWSER_SEARCH = 0xAA,
  VKEY_BROWSER_FAVORITES = 0xAB,
  VKEY_BROWSER_HOME = 0xAC,
  VKEY_VOLUME_MUTE = 0xAD,
  VKEY_VOLUME_DOWN = 0xAE,
  VKEY_VOLUME_UP = 0xAF,
  VKEY_MEDIA_NEXT_TRACK = 0xB0,
  VKEY_MEDIA_PREV_TRACK = 0xB1,
  VKEY_MEDIA_STOP = 0xB2,
  VKEY_MEDIA_PLAY_PAUSE = 0xB3,
  VKEY_MEDIA_LAUNCH_MAIL = 0xB4,
  VKEY_MEDIA_LAUNCH_MEDIA_SELECT = 0xB5,
  VKEY_MEDIA_LAUNCH_APP1 = 0xB6,
  VKEY_MEDIA_LAUNCH_APP2 = 0xB7,
  VKEY_OEM_1 = 0xBA,
  VKEY_OEM_PLUS = 0xBB,
  VKEY_OEM_COMMA = 0xBC,
  VKEY_OEM_MINUS = 0xBD,
  VKEY_OEM_PERIOD = 0xBE,
  VKEY_OEM_2 = 0xBF,
  VKEY_OEM_3 = 0xC0,
  VKEY_OEM_4 = 0xDB,
  VKEY_OEM_5 = 0xDC,
  VKEY_OEM_6 = 0xDD,
  VKEY_OEM_7 = 0xDE,
  VKEY_OEM_8 = 0xDF,
  VKEY_OEM_102 = 0xE2,
  VKEY_OEM_103 = 0xE3,  // GTV KEYCODE_MEDIA_REWIND
  VKEY_OEM_104 = 0xE4,  // GTV KEYCODE_MEDIA_FAST_FORWARD
  VKEY_PROCESSKEY = 0xE5,
  VKEY_PACKET = 0xE7,
  VKEY_DBE_SBCSCHAR = 0xF3,
  VKEY_DBE_DBCSCHAR = 0xF4,
  VKEY_ATTN = 0xF6,
  VKEY_CRSEL = 0xF7,
  VKEY_EXSEL = 0xF8,
  VKEY_EREOF = 0xF9,
  VKEY_PLAY = 0xFA,
  VKEY_ZOOM = 0xFB,
  VKEY_NONAME = 0xFC,
  VKEY_PA1 = 0xFD,
  VKEY_OEM_CLEAR = 0xFE,
  VKEY_UNKNOWN = 0,

  // POSIX specific VKEYs. Note that as of Windows SDK 7.1, 0x97-9F, 0xD8-DA,
  // and 0xE8 are unassigned.
  VKEY_WLAN = 0x97,
  VKEY_POWER = 0x98,
  VKEY_BRIGHTNESS_DOWN = 0xD8,
  VKEY_BRIGHTNESS_UP = 0xD9,
  VKEY_KBD_BRIGHTNESS_DOWN = 0xDA,
  VKEY_KBD_BRIGHTNESS_UP = 0xE8,

  // Windows does not have a specific key code for AltGr. We use the unused 0xE1
  // (VK_OEM_AX) code to represent AltGr, matching the behaviour of Firefox on
  // Linux.
  VKEY_ALTGR = 0xE1,
  // Windows does not have a specific key code for Compose. We use the unused
  // 0xE6 (VK_ICO_CLEAR) code to represent Compose.
  VKEY_COMPOSE = 0xE6,
};

// From ui/events/keycodes/keyboard_code_conversion_x.cc.
KeyboardCode KeyboardCodeFromXKeysym(unsigned int keysym) {
  switch (keysym) {
    case XK_BackSpace:
      return VKEY_BACK;
    case XK_Delete:
    case XK_KP_Delete:
      return VKEY_DELETE;
    case XK_Tab:
    case XK_KP_Tab:
    case XK_ISO_Left_Tab:
    case XK_3270_BackTab:
      return VKEY_TAB;
    case XK_Linefeed:
    case XK_Return:
    case XK_KP_Enter:
    case XK_ISO_Enter:
      return VKEY_RETURN;
    case XK_Clear:
    case XK_KP_Begin:  // NumPad 5 without Num Lock, for crosbug.com/29169.
      return VKEY_CLEAR;
    case XK_KP_Space:
    case XK_space:
      return VKEY_SPACE;
    case XK_Home:
    case XK_KP_Home:
      return VKEY_HOME;
    case XK_End:
    case XK_KP_End:
      return VKEY_END;
    case XK_Page_Up:
    case XK_KP_Page_Up:  // aka XK_KP_Prior
      return VKEY_PRIOR;
    case XK_Page_Down:
    case XK_KP_Page_Down:  // aka XK_KP_Next
      return VKEY_NEXT;
    case XK_Left:
    case XK_KP_Left:
      return VKEY_LEFT;
    case XK_Right:
    case XK_KP_Right:
      return VKEY_RIGHT;
    case XK_Down:
    case XK_KP_Down:
      return VKEY_DOWN;
    case XK_Up:
    case XK_KP_Up:
      return VKEY_UP;
    case XK_Escape:
      return VKEY_ESCAPE;
    case XK_Kana_Lock:
    case XK_Kana_Shift:
      return VKEY_KANA;
    case XK_Hangul:
      return VKEY_HANGUL;
    case XK_Hangul_Hanja:
      return VKEY_HANJA;
    case XK_Kanji:
      return VKEY_KANJI;
    case XK_Henkan:
      return VKEY_CONVERT;
    case XK_Muhenkan:
      return VKEY_NONCONVERT;
    case XK_Zenkaku_Hankaku:
      return VKEY_DBE_DBCSCHAR;
    case XK_A:
    case XK_a:
      return VKEY_A;
    case XK_B:
    case XK_b:
      return VKEY_B;
    case XK_C:
    case XK_c:
      return VKEY_C;
    case XK_D:
    case XK_d:
      return VKEY_D;
    case XK_E:
    case XK_e:
      return VKEY_E;
    case XK_F:
    case XK_f:
      return VKEY_F;
    case XK_G:
    case XK_g:
      return VKEY_G;
    case XK_H:
    case XK_h:
      return VKEY_H;
    case XK_I:
    case XK_i:
      return VKEY_I;
    case XK_J:
    case XK_j:
      return VKEY_J;
    case XK_K:
    case XK_k:
      return VKEY_K;
    case XK_L:
    case XK_l:
      return VKEY_L;
    case XK_M:
    case XK_m:
      return VKEY_M;
    case XK_N:
    case XK_n:
      return VKEY_N;
    case XK_O:
    case XK_o:
      return VKEY_O;
    case XK_P:
    case XK_p:
      return VKEY_P;
    case XK_Q:
    case XK_q:
      return VKEY_Q;
    case XK_R:
    case XK_r:
      return VKEY_R;
    case XK_S:
    case XK_s:
      return VKEY_S;
    case XK_T:
    case XK_t:
      return VKEY_T;
    case XK_U:
    case XK_u:
      return VKEY_U;
    case XK_V:
    case XK_v:
      return VKEY_V;
    case XK_W:
    case XK_w:
      return VKEY_W;
    case XK_X:
    case XK_x:
      return VKEY_X;
    case XK_Y:
    case XK_y:
      return VKEY_Y;
    case XK_Z:
    case XK_z:
      return VKEY_Z;

    case XK_0:
    case XK_1:
    case XK_2:
    case XK_3:
    case XK_4:
    case XK_5:
    case XK_6:
    case XK_7:
    case XK_8:
    case XK_9:
      return static_cast<KeyboardCode>(VKEY_0 + (keysym - XK_0));

    case XK_parenright:
      return VKEY_0;
    case XK_exclam:
      return VKEY_1;
    case XK_at:
      return VKEY_2;
    case XK_numbersign:
      return VKEY_3;
    case XK_dollar:
      return VKEY_4;
    case XK_percent:
      return VKEY_5;
    case XK_asciicircum:
      return VKEY_6;
    case XK_ampersand:
      return VKEY_7;
    case XK_asterisk:
      return VKEY_8;
    case XK_parenleft:
      return VKEY_9;

    case XK_KP_0:
    case XK_KP_1:
    case XK_KP_2:
    case XK_KP_3:
    case XK_KP_4:
    case XK_KP_5:
    case XK_KP_6:
    case XK_KP_7:
    case XK_KP_8:
    case XK_KP_9:
      return static_cast<KeyboardCode>(VKEY_NUMPAD0 + (keysym - XK_KP_0));

    case XK_multiply:
    case XK_KP_Multiply:
      return VKEY_MULTIPLY;
    case XK_KP_Add:
      return VKEY_ADD;
    case XK_KP_Separator:
      return VKEY_SEPARATOR;
    case XK_KP_Subtract:
      return VKEY_SUBTRACT;
    case XK_KP_Decimal:
      return VKEY_DECIMAL;
    case XK_KP_Divide:
      return VKEY_DIVIDE;
    case XK_KP_Equal:
    case XK_equal:
    case XK_plus:
      return VKEY_OEM_PLUS;
    case XK_comma:
    case XK_less:
      return VKEY_OEM_COMMA;
    case XK_minus:
    case XK_underscore:
      return VKEY_OEM_MINUS;
    case XK_greater:
    case XK_period:
      return VKEY_OEM_PERIOD;
    case XK_colon:
    case XK_semicolon:
      return VKEY_OEM_1;
    case XK_question:
    case XK_slash:
      return VKEY_OEM_2;
    case XK_asciitilde:
    case XK_quoteleft:
      return VKEY_OEM_3;
    case XK_bracketleft:
    case XK_braceleft:
      return VKEY_OEM_4;
    case XK_backslash:
    case XK_bar:
      return VKEY_OEM_5;
    case XK_bracketright:
    case XK_braceright:
      return VKEY_OEM_6;
    case XK_quoteright:
    case XK_quotedbl:
      return VKEY_OEM_7;
    case XK_ISO_Level5_Shift:
      return VKEY_OEM_8;
    case XK_Shift_L:
    case XK_Shift_R:
      return VKEY_SHIFT;
    case XK_Control_L:
    case XK_Control_R:
      return VKEY_CONTROL;
    case XK_Meta_L:
    case XK_Meta_R:
    case XK_Alt_L:
    case XK_Alt_R:
      return VKEY_MENU;
    case XK_ISO_Level3_Shift:
      return VKEY_ALTGR;
    case XK_Multi_key:
      return VKEY_COMPOSE;
    case XK_Pause:
      return VKEY_PAUSE;
    case XK_Caps_Lock:
      return VKEY_CAPITAL;
    case XK_Num_Lock:
      return VKEY_NUMLOCK;
    case XK_Scroll_Lock:
      return VKEY_SCROLL;
    case XK_Select:
      return VKEY_SELECT;
    case XK_Print:
      return VKEY_PRINT;
    case XK_Execute:
      return VKEY_EXECUTE;
    case XK_Insert:
    case XK_KP_Insert:
      return VKEY_INSERT;
    case XK_Help:
      return VKEY_HELP;
    case XK_Super_L:
      return VKEY_LWIN;
    case XK_Super_R:
      return VKEY_RWIN;
    case XK_Menu:
      return VKEY_APPS;
    case XK_F1:
    case XK_F2:
    case XK_F3:
    case XK_F4:
    case XK_F5:
    case XK_F6:
    case XK_F7:
    case XK_F8:
    case XK_F9:
    case XK_F10:
    case XK_F11:
    case XK_F12:
    case XK_F13:
    case XK_F14:
    case XK_F15:
    case XK_F16:
    case XK_F17:
    case XK_F18:
    case XK_F19:
    case XK_F20:
    case XK_F21:
    case XK_F22:
    case XK_F23:
    case XK_F24:
      return static_cast<KeyboardCode>(VKEY_F1 + (keysym - XK_F1));
    case XK_KP_F1:
    case XK_KP_F2:
    case XK_KP_F3:
    case XK_KP_F4:
      return static_cast<KeyboardCode>(VKEY_F1 + (keysym - XK_KP_F1));

    case XK_guillemotleft:
    case XK_guillemotright:
    case XK_degree:
    // In the case of canadian multilingual keyboard layout, VKEY_OEM_102 is
    // assigned to ugrave key.
    case XK_ugrave:
    case XK_Ugrave:
    case XK_brokenbar:
      return VKEY_OEM_102;  // international backslash key in 102 keyboard.

    // When evdev is in use, /usr/share/X11/xkb/symbols/inet maps F13-18 keys
    // to the special XF86XK symbols to support Microsoft Ergonomic keyboards:
    // https://bugs.freedesktop.org/show_bug.cgi?id=5783
    // In Chrome, we map these X key symbols back to F13-18 since we don't have
    // VKEYs for these XF86XK symbols.
    case XF86XK_Tools:
      return VKEY_F13;
    case XF86XK_Launch5:
      return VKEY_F14;
    case XF86XK_Launch6:
      return VKEY_F15;
    case XF86XK_Launch7:
      return VKEY_F16;
    case XF86XK_Launch8:
      return VKEY_F17;
    case XF86XK_Launch9:
      return VKEY_F18;
    case XF86XK_Refresh:
    case XF86XK_History:
    case XF86XK_OpenURL:
    case XF86XK_AddFavorite:
    case XF86XK_Go:
    case XF86XK_ZoomIn:
    case XF86XK_ZoomOut:
      // ui::AcceleratorGtk tries to convert the XF86XK_ keysyms on Chrome
      // startup. It's safe to return VKEY_UNKNOWN here since ui::AcceleratorGtk
      // also checks a Gdk keysym. http://crbug.com/109843
      return VKEY_UNKNOWN;
    // For supporting multimedia buttons on a USB keyboard.
    case XF86XK_Back:
      return VKEY_BROWSER_BACK;
    case XF86XK_Forward:
      return VKEY_BROWSER_FORWARD;
    case XF86XK_Reload:
      return VKEY_BROWSER_REFRESH;
    case XF86XK_Stop:
      return VKEY_BROWSER_STOP;
    case XF86XK_Search:
      return VKEY_BROWSER_SEARCH;
    case XF86XK_Favorites:
      return VKEY_BROWSER_FAVORITES;
    case XF86XK_HomePage:
      return VKEY_BROWSER_HOME;
    case XF86XK_AudioMute:
      return VKEY_VOLUME_MUTE;
    case XF86XK_AudioLowerVolume:
      return VKEY_VOLUME_DOWN;
    case XF86XK_AudioRaiseVolume:
      return VKEY_VOLUME_UP;
    case XF86XK_AudioNext:
      return VKEY_MEDIA_NEXT_TRACK;
    case XF86XK_AudioPrev:
      return VKEY_MEDIA_PREV_TRACK;
    case XF86XK_AudioStop:
      return VKEY_MEDIA_STOP;
    case XF86XK_AudioPlay:
      return VKEY_MEDIA_PLAY_PAUSE;
    case XF86XK_Mail:
      return VKEY_MEDIA_LAUNCH_MAIL;
    case XF86XK_LaunchA:  // F3 on an Apple keyboard.
      return VKEY_MEDIA_LAUNCH_APP1;
    case XF86XK_LaunchB:  // F4 on an Apple keyboard.
    case XF86XK_Calculator:
      return VKEY_MEDIA_LAUNCH_APP2;
    case XF86XK_WLAN:
      return VKEY_WLAN;
    case XF86XK_PowerOff:
      return VKEY_POWER;
    case XF86XK_MonBrightnessDown:
      return VKEY_BRIGHTNESS_DOWN;
    case XF86XK_MonBrightnessUp:
      return VKEY_BRIGHTNESS_UP;
    case XF86XK_KbdBrightnessDown:
      return VKEY_KBD_BRIGHTNESS_DOWN;
    case XF86XK_KbdBrightnessUp:
      return VKEY_KBD_BRIGHTNESS_UP;

      // TODO(sad): some keycodes are still missing.
  }
  return VKEY_UNKNOWN;
}

// From content/browser/renderer_host/input/web_input_event_util_posix.cc.
KeyboardCode GetWindowsKeyCodeWithoutLocation(KeyboardCode key_code) {
  switch (key_code) {
    case VKEY_LCONTROL:
    case VKEY_RCONTROL:
      return VKEY_CONTROL;
    case VKEY_LSHIFT:
    case VKEY_RSHIFT:
      return VKEY_SHIFT;
    case VKEY_LMENU:
    case VKEY_RMENU:
      return VKEY_MENU;
    default:
      return key_code;
  }
}

unsigned int GetUnicodeKeySym(char16_t key_char) {
  if (!HasDefinedKeyChar(key_char))
    return NoSymbol;
  if ((key_char >= 0x20 && key_char <= 0x7E) ||
      (key_char >= 0xA0 && key_char <= 0xFF))
    return key_char;
  return 0x01000000U | key_char;
}

unsigned int GetAwtLinuxKeySym(int key_code, int key_location) {
  if ((key_code >= '0' && key_code <= '9') ||
      (key_code >= 'A' && key_code <= 'Z'))
    return static_cast<unsigned int>(key_code);
  if (key_code >= awt::kVkF1 && key_code <= awt::kVkF12)
    return XK_F1 + key_code - awt::kVkF1;
  if (key_code >= awt::kVkF13 && key_code <= awt::kVkF24)
    return XK_F13 + key_code - awt::kVkF13;
  if (key_code >= awt::kVkNumpad0 && key_code <= awt::kVkNumpad9)
    return XK_KP_0 + key_code - awt::kVkNumpad0;

  switch (key_code) {
    case awt::kVkCancel:
      return XK_Cancel;
    case awt::kVkBackSpace:
      return XK_BackSpace;
    case awt::kVkTab:
      return XK_Tab;
    case awt::kVkEnter:
      return key_location == awt::kKeyLocationNumpad ? XK_KP_Enter : XK_Return;
    case awt::kVkClear:
      return XK_Clear;
    case awt::kVkShift:
      return key_location == awt::kKeyLocationRight ? XK_Shift_R : XK_Shift_L;
    case awt::kVkControl:
      return key_location == awt::kKeyLocationRight ? XK_Control_R
                                                    : XK_Control_L;
    case awt::kVkAlt:
      return key_location == awt::kKeyLocationRight ? XK_Alt_R : XK_Alt_L;
    case awt::kVkPause:
      return XK_Pause;
    case awt::kVkCapsLock:
      return XK_Caps_Lock;
    case awt::kVkEscape:
      return XK_Escape;
    case awt::kVkSpace:
      return XK_space;
    case awt::kVkPageUp:
      return XK_Page_Up;
    case awt::kVkPageDown:
      return XK_Page_Down;
    case awt::kVkEnd:
      return XK_End;
    case awt::kVkHome:
      return XK_Home;
    case awt::kVkLeft:
      return XK_Left;
    case awt::kVkUp:
      return XK_Up;
    case awt::kVkRight:
      return XK_Right;
    case awt::kVkDown:
      return XK_Down;
    case ',':
    case '-':
    case '.':
    case '/':
    case ';':
    case '=':
    case '[':
    case '\\':
    case ']':
      return static_cast<unsigned int>(key_code);
    case awt::kVkBackQuote:
      return XK_grave;
    case awt::kVkQuote:
      return XK_apostrophe;
    case awt::kVkMultiply:
      return XK_KP_Multiply;
    case awt::kVkAdd:
      return XK_KP_Add;
    case awt::kVkSeparator:
      return XK_KP_Separator;
    case awt::kVkSubtract:
      return XK_KP_Subtract;
    case awt::kVkDecimal:
      return XK_KP_Decimal;
    case awt::kVkDivide:
      return XK_KP_Divide;
    case awt::kVkDelete:
      return XK_Delete;
    case awt::kVkDeadGrave:
      return XK_dead_grave;
    case awt::kVkDeadAcute:
      return XK_dead_acute;
    case awt::kVkDeadCircumflex:
      return XK_dead_circumflex;
    case awt::kVkDeadTilde:
      return XK_dead_tilde;
    case awt::kVkDeadMacron:
      return XK_dead_macron;
    case awt::kVkDeadBreve:
      return XK_dead_breve;
    case awt::kVkDeadAboveDot:
      return XK_dead_abovedot;
    case awt::kVkDeadDiaeresis:
      return XK_dead_diaeresis;
    case awt::kVkDeadAboveRing:
      return XK_dead_abovering;
    case awt::kVkDeadDoubleAcute:
      return XK_dead_doubleacute;
    case awt::kVkDeadCaron:
      return XK_dead_caron;
    case awt::kVkDeadCedilla:
      return XK_dead_cedilla;
    case awt::kVkDeadOgonek:
      return XK_dead_ogonek;
    case awt::kVkDeadIota:
      return XK_dead_iota;
    case awt::kVkDeadVoicedSound:
      return XK_dead_voiced_sound;
    case awt::kVkDeadSemivoicedSound:
      return XK_dead_semivoiced_sound;
    case awt::kVkNumLock:
      return XK_Num_Lock;
    case awt::kVkScrollLock:
      return XK_Scroll_Lock;
    case awt::kVkPrintScreen:
      return XK_Print;
    case awt::kVkInsert:
      return XK_Insert;
    case awt::kVkHelp:
      return XK_Help;
    case awt::kVkMeta:
    case awt::kVkWindows:
      return key_location == awt::kKeyLocationRight ? XK_Super_R : XK_Super_L;
    case awt::kVkKpUp:
      return XK_KP_Up;
    case awt::kVkKpDown:
      return XK_KP_Down;
    case awt::kVkKpLeft:
      return XK_KP_Left;
    case awt::kVkKpRight:
      return XK_KP_Right;
    case awt::kVkContextMenu:
      return XK_Menu;
    case awt::kVkCompose:
      return XK_Multi_key;
    case awt::kVkAltGraph:
      return XK_ISO_Level3_Shift;
    default:
      return NoSymbol;
  }
}

unsigned int GetGlfwLinuxKeySym(int key_code) {
  // GLFW printable key codes describe the physical US-layout identity. Keep
  // that identity separate from CefKeyEvent.keyChar so custom layout text does
  // not turn a physical KeyA event into VKEY_UNKNOWN.
  if (IsGlfwStandardPrintableKey(key_code))
    return static_cast<unsigned int>(key_code);
  if (key_code >= glfw::kKeyF1 && key_code <= glfw::kKeyF25)
    return XK_F1 + key_code - glfw::kKeyF1;
  if (key_code >= glfw::kKeyKp0 && key_code <= glfw::kKeyKp9)
    return XK_KP_0 + key_code - glfw::kKeyKp0;

  switch (key_code) {
    case glfw::kKeyEscape:
      return XK_Escape;
    case glfw::kKeyEnter:
      return XK_Return;
    case glfw::kKeyTab:
      return XK_Tab;
    case glfw::kKeyBackspace:
      return XK_BackSpace;
    case glfw::kKeyInsert:
      return XK_Insert;
    case glfw::kKeyDelete:
      return XK_Delete;
    case glfw::kKeyRight:
      return XK_Right;
    case glfw::kKeyLeft:
      return XK_Left;
    case glfw::kKeyDown:
      return XK_Down;
    case glfw::kKeyUp:
      return XK_Up;
    case glfw::kKeyPageUp:
      return XK_Page_Up;
    case glfw::kKeyPageDown:
      return XK_Page_Down;
    case glfw::kKeyHome:
      return XK_Home;
    case glfw::kKeyEnd:
      return XK_End;
    case glfw::kKeyCapsLock:
      return XK_Caps_Lock;
    case glfw::kKeyScrollLock:
      return XK_Scroll_Lock;
    case glfw::kKeyNumLock:
      return XK_Num_Lock;
    case glfw::kKeyPrintScreen:
      return XK_Print;
    case glfw::kKeyPause:
      return XK_Pause;
    case glfw::kKeyKpDecimal:
      return XK_KP_Decimal;
    case glfw::kKeyKpDivide:
      return XK_KP_Divide;
    case glfw::kKeyKpMultiply:
      return XK_KP_Multiply;
    case glfw::kKeyKpSubtract:
      return XK_KP_Subtract;
    case glfw::kKeyKpAdd:
      return XK_KP_Add;
    case glfw::kKeyKpEnter:
      return XK_KP_Enter;
    case glfw::kKeyKpEqual:
      return XK_KP_Equal;
    case glfw::kKeyLeftShift:
      return XK_Shift_L;
    case glfw::kKeyLeftControl:
      return XK_Control_L;
    case glfw::kKeyLeftAlt:
      return XK_Alt_L;
    case glfw::kKeyLeftSuper:
      return XK_Super_L;
    case glfw::kKeyRightShift:
      return XK_Shift_R;
    case glfw::kKeyRightControl:
      return XK_Control_R;
    case glfw::kKeyRightAlt:
      return XK_Alt_R;
    case glfw::kKeyRightSuper:
      return XK_Super_R;
    case glfw::kKeyMenu:
      return XK_Menu;
    default:
      return NoSymbol;
  }
}

unsigned int GetLinuxKeySym(int key_code, int key_location, char16_t key_char, bool typed, InputEventSemantics semantics) {
  if (typed)
    return GetUnicodeKeySym(key_char);
  const unsigned int special_key =
      semantics == InputEventSemantics::kAwt
          ? GetAwtLinuxKeySym(key_code, key_location)
          : GetGlfwLinuxKeySym(key_code);
  return special_key == NoSymbol ? GetUnicodeKeySym(key_char) : special_key;
}

#endif  // defined(OS_LINUX)

#if defined(OS_MACOSX)
// Convert an ANSI character to a Mac key code.
int GetMacKeyCodeFromChar(int key_char) {
  switch (key_char) {
    case ' ':
      return kVK_Space;

    case '0':
    case ')':
      return kVK_ANSI_0;
    case '1':
    case '!':
      return kVK_ANSI_1;
    case '2':
    case '@':
      return kVK_ANSI_2;
    case '3':
    case '#':
      return kVK_ANSI_3;
    case '4':
    case '$':
      return kVK_ANSI_4;
    case '5':
    case '%':
      return kVK_ANSI_5;
    case '6':
    case '^':
      return kVK_ANSI_6;
    case '7':
    case '&':
      return kVK_ANSI_7;
    case '8':
    case '*':
      return kVK_ANSI_8;
    case '9':
    case '(':
      return kVK_ANSI_9;

    case 'a':
    case 'A':
      return kVK_ANSI_A;
    case 'b':
    case 'B':
      return kVK_ANSI_B;
    case 'c':
    case 'C':
      return kVK_ANSI_C;
    case 'd':
    case 'D':
      return kVK_ANSI_D;
    case 'e':
    case 'E':
      return kVK_ANSI_E;
    case 'f':
    case 'F':
      return kVK_ANSI_F;
    case 'g':
    case 'G':
      return kVK_ANSI_G;
    case 'h':
    case 'H':
      return kVK_ANSI_H;
    case 'i':
    case 'I':
      return kVK_ANSI_I;
    case 'j':
    case 'J':
      return kVK_ANSI_J;
    case 'k':
    case 'K':
      return kVK_ANSI_K;
    case 'l':
    case 'L':
      return kVK_ANSI_L;
    case 'm':
    case 'M':
      return kVK_ANSI_M;
    case 'n':
    case 'N':
      return kVK_ANSI_N;
    case 'o':
    case 'O':
      return kVK_ANSI_O;
    case 'p':
    case 'P':
      return kVK_ANSI_P;
    case 'q':
    case 'Q':
      return kVK_ANSI_Q;
    case 'r':
    case 'R':
      return kVK_ANSI_R;
    case 's':
    case 'S':
      return kVK_ANSI_S;
    case 't':
    case 'T':
      return kVK_ANSI_T;
    case 'u':
    case 'U':
      return kVK_ANSI_U;
    case 'v':
    case 'V':
      return kVK_ANSI_V;
    case 'w':
    case 'W':
      return kVK_ANSI_W;
    case 'x':
    case 'X':
      return kVK_ANSI_X;
    case 'y':
    case 'Y':
      return kVK_ANSI_Y;
    case 'z':
    case 'Z':
      return kVK_ANSI_Z;

    // U.S. Specific mappings.  Mileage may vary.
    case ';':
    case ':':
      return kVK_ANSI_Semicolon;
    case '=':
    case '+':
      return kVK_ANSI_Equal;
    case ',':
    case '<':
      return kVK_ANSI_Comma;
    case '-':
    case '_':
      return kVK_ANSI_Minus;
    case '.':
    case '>':
      return kVK_ANSI_Period;
    case '/':
    case '?':
      return kVK_ANSI_Slash;
    case '`':
    case '~':
      return kVK_ANSI_Grave;
    case '[':
    case '{':
      return kVK_ANSI_LeftBracket;
    case '\\':
    case '|':
      return kVK_ANSI_Backslash;
    case ']':
    case '}':
      return kVK_ANSI_RightBracket;
    case '\'':
    case '"':
      return kVK_ANSI_Quote;
  }

  return -1;
}

int GetMacFunctionKeyCode(int function_number) {
  switch (function_number) {
    case 1:
      return kVK_F1;
    case 2:
      return kVK_F2;
    case 3:
      return kVK_F3;
    case 4:
      return kVK_F4;
    case 5:
      return kVK_F5;
    case 6:
      return kVK_F6;
    case 7:
      return kVK_F7;
    case 8:
      return kVK_F8;
    case 9:
      return kVK_F9;
    case 10:
      return kVK_F10;
    case 11:
      return kVK_F11;
    case 12:
      return kVK_F12;
    case 13:
      return kVK_F13;
    case 14:
      return kVK_F14;
    case 15:
      return kVK_F15;
    case 16:
      return kVK_F16;
    case 17:
      return kVK_F17;
    case 18:
      return kVK_F18;
    case 19:
      return kVK_F19;
    case 20:
      return kVK_F20;
    default:
      return -1;
  }
}

int GetMacKeypadDigitKeyCode(int digit) {
  switch (digit) {
    case 0:
      return kVK_ANSI_Keypad0;
    case 1:
      return kVK_ANSI_Keypad1;
    case 2:
      return kVK_ANSI_Keypad2;
    case 3:
      return kVK_ANSI_Keypad3;
    case 4:
      return kVK_ANSI_Keypad4;
    case 5:
      return kVK_ANSI_Keypad5;
    case 6:
      return kVK_ANSI_Keypad6;
    case 7:
      return kVK_ANSI_Keypad7;
    case 8:
      return kVK_ANSI_Keypad8;
    case 9:
      return kVK_ANSI_Keypad9;
    default:
      return -1;
  }
}

int GetAwtMacKeyCode(int key_code, int key_location) {
  if ((key_code >= '0' && key_code <= '9') ||
      (key_code >= 'A' && key_code <= 'Z') || key_code == ',' ||
      key_code == '-' || key_code == '.' || key_code == '/' ||
      key_code == ';' || key_code == '=' || key_code == '[' ||
      key_code == '\\' || key_code == ']')
    return GetMacKeyCodeFromChar(key_code);
  if (key_code >= awt::kVkF1 && key_code <= awt::kVkF12)
    return GetMacFunctionKeyCode(1 + key_code - awt::kVkF1);
  if (key_code >= awt::kVkF13 && key_code <= awt::kVkF24)
    return GetMacFunctionKeyCode(13 + key_code - awt::kVkF13);
  if (key_code >= awt::kVkNumpad0 && key_code <= awt::kVkNumpad9)
    return GetMacKeypadDigitKeyCode(key_code - awt::kVkNumpad0);

  switch (key_code) {
    case awt::kVkCancel:
      return kVK_Escape;
    case awt::kVkBackSpace:
      return kVK_Delete;
    case awt::kVkTab:
      return kVK_Tab;
    case awt::kVkEnter:
      return key_location == awt::kKeyLocationNumpad
                 ? static_cast<int>(kVK_ANSI_KeypadEnter)
                 : static_cast<int>(kVK_Return);
    case awt::kVkClear:
      return kVK_ANSI_KeypadClear;
    case awt::kVkShift:
      return key_location == awt::kKeyLocationRight
                 ? static_cast<int>(kVK_RightShift)
                 : static_cast<int>(kVK_Shift);
    case awt::kVkControl:
      return key_location == awt::kKeyLocationRight
                 ? static_cast<int>(kVK_RightControl)
                 : static_cast<int>(kVK_Control);
    case awt::kVkAlt:
    case awt::kVkAltGraph:
      return key_location == awt::kKeyLocationRight
                 ? static_cast<int>(kVK_RightOption)
                 : static_cast<int>(kVK_Option);
    case awt::kVkPause:
      return kVK_F15;
    case awt::kVkCapsLock:
      return kVK_CapsLock;
    case awt::kVkEscape:
      return kVK_Escape;
    case awt::kVkSpace:
      return kVK_Space;
    case awt::kVkPageUp:
      return kVK_PageUp;
    case awt::kVkPageDown:
      return kVK_PageDown;
    case awt::kVkEnd:
      return kVK_End;
    case awt::kVkHome:
      return kVK_Home;
    case awt::kVkLeft:
      return kVK_LeftArrow;
    case awt::kVkUp:
      return kVK_UpArrow;
    case awt::kVkRight:
      return kVK_RightArrow;
    case awt::kVkDown:
      return kVK_DownArrow;
    case awt::kVkMultiply:
      return kVK_ANSI_KeypadMultiply;
    case awt::kVkAdd:
      return kVK_ANSI_KeypadPlus;
    case awt::kVkSeparator:
      return kVK_JIS_KeypadComma;
    case awt::kVkSubtract:
      return kVK_ANSI_KeypadMinus;
    case awt::kVkDecimal:
      return kVK_ANSI_KeypadDecimal;
    case awt::kVkDivide:
      return kVK_ANSI_KeypadDivide;
    case awt::kVkDelete:
      return kVK_ForwardDelete;
    // Public macOS AWT events do not expose a portable hardware code for dead
    // keys. These US-layout approximations keep the raw event non-dropping
    // while typed Unicode remains exact.
    case awt::kVkDeadGrave:
    case awt::kVkDeadTilde:
      return kVK_ANSI_Grave;
    case awt::kVkDeadAcute:
      return kVK_ANSI_Quote;
    case awt::kVkDeadCircumflex:
      return kVK_ANSI_6;
    case awt::kVkDeadMacron:
      return kVK_ANSI_Minus;
    case awt::kVkDeadBreve:
      return kVK_ANSI_B;
    case awt::kVkDeadAboveDot:
      return kVK_ANSI_Period;
    case awt::kVkDeadDiaeresis:
      return kVK_ANSI_U;
    case awt::kVkDeadAboveRing:
      return kVK_ANSI_A;
    case awt::kVkDeadDoubleAcute:
      return kVK_ANSI_Quote;
    case awt::kVkDeadCaron:
      return kVK_ANSI_C;
    case awt::kVkDeadCedilla:
      return kVK_ANSI_C;
    case awt::kVkDeadOgonek:
      return kVK_ANSI_O;
    case awt::kVkDeadIota:
      return kVK_ANSI_I;
    case awt::kVkDeadVoicedSound:
    case awt::kVkDeadSemivoicedSound:
      return kVK_ANSI_Quote;
    case awt::kVkNumLock:
      return kVK_ANSI_KeypadClear;
    case awt::kVkScrollLock:
      return kVK_F14;
    case awt::kVkPrintScreen:
      return kVK_F13;
    case awt::kVkInsert:
    case awt::kVkHelp:
      return kVK_Help;
    case awt::kVkMeta:
    case awt::kVkWindows:
      return key_location == awt::kKeyLocationRight
                 ? static_cast<int>(kVK_RightCommand)
                 : static_cast<int>(kVK_Command);
    case awt::kVkBackQuote:
      return kVK_ANSI_Grave;
    case awt::kVkQuote:
      return kVK_ANSI_Quote;
    case awt::kVkKpUp:
      return kVK_ANSI_Keypad8;
    case awt::kVkKpDown:
      return kVK_ANSI_Keypad2;
    case awt::kVkKpLeft:
      return kVK_ANSI_Keypad4;
    case awt::kVkKpRight:
      return kVK_ANSI_Keypad6;
    case awt::kVkContextMenu:
      return -1;
    case awt::kVkCompose:
      return kVK_RightOption;
    default:
      return -1;
  }
}

int GetGlfwMacKeyCode(int key_code) {
  if (key_code >= glfw::kKeyF1 && key_code <= glfw::kKeyF25)
    return GetMacFunctionKeyCode(1 + key_code - glfw::kKeyF1);
  if (key_code >= glfw::kKeyKp0 && key_code <= glfw::kKeyKp9)
    return GetMacKeypadDigitKeyCode(key_code - glfw::kKeyKp0);

  switch (key_code) {
    case glfw::kKeyEscape:
      return kVK_Escape;
    case glfw::kKeyEnter:
      return kVK_Return;
    case glfw::kKeyTab:
      return kVK_Tab;
    case glfw::kKeyBackspace:
      return kVK_Delete;
    case glfw::kKeyInsert:
      return kVK_Help;
    case glfw::kKeyDelete:
      return kVK_ForwardDelete;
    case glfw::kKeyRight:
      return kVK_RightArrow;
    case glfw::kKeyLeft:
      return kVK_LeftArrow;
    case glfw::kKeyDown:
      return kVK_DownArrow;
    case glfw::kKeyUp:
      return kVK_UpArrow;
    case glfw::kKeyPageUp:
      return kVK_PageUp;
    case glfw::kKeyPageDown:
      return kVK_PageDown;
    case glfw::kKeyHome:
      return kVK_Home;
    case glfw::kKeyEnd:
      return kVK_End;
    case glfw::kKeyCapsLock:
      return kVK_CapsLock;
    case glfw::kKeyScrollLock:
      return kVK_F14;
    case glfw::kKeyNumLock:
      return kVK_ANSI_KeypadClear;
    case glfw::kKeyPrintScreen:
      return kVK_F13;
    case glfw::kKeyPause:
      return kVK_F15;
    case glfw::kKeyKpDecimal:
      return kVK_ANSI_KeypadDecimal;
    case glfw::kKeyKpDivide:
      return kVK_ANSI_KeypadDivide;
    case glfw::kKeyKpMultiply:
      return kVK_ANSI_KeypadMultiply;
    case glfw::kKeyKpSubtract:
      return kVK_ANSI_KeypadMinus;
    case glfw::kKeyKpAdd:
      return kVK_ANSI_KeypadPlus;
    case glfw::kKeyKpEnter:
      return kVK_ANSI_KeypadEnter;
    case glfw::kKeyKpEqual:
      return kVK_ANSI_KeypadEquals;
    case glfw::kKeyLeftShift:
      return kVK_Shift;
    case glfw::kKeyLeftControl:
      return kVK_Control;
    case glfw::kKeyLeftAlt:
      return kVK_Option;
    case glfw::kKeyLeftSuper:
      return kVK_Command;
    case glfw::kKeyRightShift:
      return kVK_RightShift;
    case glfw::kKeyRightControl:
      return kVK_RightControl;
    case glfw::kKeyRightAlt:
      return kVK_RightOption;
    case glfw::kKeyRightSuper:
      return kVK_RightCommand;
    case glfw::kKeyMenu:
      return -1;
    default:
      return -1;
  }
}

int GetMacKeyCode(int key_code, int key_location, char16_t key_char, bool typed, InputEventSemantics semantics) {
  if (typed)
    return HasDefinedKeyChar(key_char) ? GetMacKeyCodeFromChar(key_char) : -1;
  const int special_key = semantics == InputEventSemantics::kAwt
                              ? GetAwtMacKeyCode(key_code, key_location)
                              : GetGlfwMacKeyCode(key_code);
  return special_key == -1 && HasDefinedKeyChar(key_char)
             ? GetMacKeyCodeFromChar(key_char)
             : special_key;
}

int GetAwtMacWindowsKeyCode(int key_code, int key_location) {
  if ((key_code >= '0' && key_code <= '9') ||
      (key_code >= 'A' && key_code <= 'Z') ||
      (key_code >= awt::kVkF1 && key_code <= awt::kVkF12) ||
      (key_code >= awt::kVkNumpad0 && key_code <= awt::kVkNumpad9))
    return key_code;
  if (key_code >= awt::kVkF13 && key_code <= awt::kVkF24)
    return 0x7C + key_code - awt::kVkF13;

  switch (key_code) {
    case awt::kVkCancel:
      return 0x03;
    case awt::kVkBackSpace:
      return 0x08;
    case awt::kVkTab:
      return 0x09;
    case awt::kVkEnter:
      return 0x0D;
    case awt::kVkClear:
      return 0x0C;
    case awt::kVkShift:
      return 0x10;
    case awt::kVkControl:
      return 0x11;
    case awt::kVkAlt:
    case awt::kVkAltGraph:
      return 0x12;
    case awt::kVkPause:
      return 0x13;
    case awt::kVkCapsLock:
      return 0x14;
    case awt::kVkEscape:
      return 0x1B;
    case awt::kVkSpace:
      return 0x20;
    case awt::kVkPageUp:
      return 0x21;
    case awt::kVkPageDown:
      return 0x22;
    case awt::kVkEnd:
      return 0x23;
    case awt::kVkHome:
      return 0x24;
    case awt::kVkLeft:
    case awt::kVkKpLeft:
      return 0x25;
    case awt::kVkUp:
    case awt::kVkKpUp:
      return 0x26;
    case awt::kVkRight:
    case awt::kVkKpRight:
      return 0x27;
    case awt::kVkDown:
    case awt::kVkKpDown:
      return 0x28;
    case awt::kVkPrintScreen:
      return 0x2C;
    case awt::kVkInsert:
      return 0x2D;
    case awt::kVkDelete:
      return 0x2E;
    case awt::kVkHelp:
      return 0x2F;
    case awt::kVkMeta:
    case awt::kVkWindows:
      return key_location == awt::kKeyLocationRight ? 0x5C : 0x5B;
    case awt::kVkContextMenu:
    case awt::kVkCompose:
      return 0x5D;
    case awt::kVkMultiply:
      return 0x6A;
    case awt::kVkAdd:
      return 0x6B;
    case awt::kVkSeparator:
      return 0x6C;
    case awt::kVkSubtract:
      return 0x6D;
    case awt::kVkDecimal:
      return 0x6E;
    case awt::kVkDivide:
      return 0x6F;
    case awt::kVkNumLock:
      return 0x90;
    case awt::kVkScrollLock:
      return 0x91;
    case ',':
      return 0xBC;
    case '-':
      return 0xBD;
    case '.':
      return 0xBE;
    case '/':
      return 0xBF;
    case ';':
      return 0xBA;
    case '=':
      return 0xBB;
    case '[':
      return 0xDB;
    case '\\':
      return 0xDC;
    case ']':
      return 0xDD;
    case awt::kVkBackQuote:
    case awt::kVkDeadGrave:
    case awt::kVkDeadTilde:
      return 0xC0;
    case awt::kVkQuote:
    case awt::kVkDeadAcute:
    case awt::kVkDeadDoubleAcute:
    case awt::kVkDeadVoicedSound:
    case awt::kVkDeadSemivoicedSound:
      return 0xDE;
    case awt::kVkDeadCircumflex:
      return '6';
    case awt::kVkDeadMacron:
      return 0xBD;
    case awt::kVkDeadBreve:
      return 'B';
    case awt::kVkDeadAboveDot:
      return 0xBE;
    case awt::kVkDeadDiaeresis:
      return 'U';
    case awt::kVkDeadAboveRing:
      return 'A';
    case awt::kVkDeadCaron:
    case awt::kVkDeadCedilla:
      return 'C';
    case awt::kVkDeadOgonek:
      return 'O';
    case awt::kVkDeadIota:
      return 'I';
    default:
      return 0;
  }
}

int GetGlfwMacWindowsKeyCode(int key_code) {
  if ((key_code >= '0' && key_code <= '9') ||
      (key_code >= 'A' && key_code <= 'Z'))
    return key_code;
  if (key_code >= glfw::kKeyF1 && key_code < glfw::kKeyF25)
    return 0x70 + key_code - glfw::kKeyF1;
  if (key_code >= glfw::kKeyKp0 && key_code <= glfw::kKeyKp9)
    return 0x60 + key_code - glfw::kKeyKp0;

  switch (key_code) {
    case 32:
      return 0x20;
    case 39:
      return 0xDE;
    case 44:
      return 0xBC;
    case 45:
      return 0xBD;
    case 46:
      return 0xBE;
    case 47:
      return 0xBF;
    case 59:
      return 0xBA;
    case 61:
      return 0xBB;
    case 91:
      return 0xDB;
    case 92:
      return 0xDC;
    case 93:
      return 0xDD;
    case 96:
      return 0xC0;
    case glfw::kKeyEscape:
      return 0x1B;
    case glfw::kKeyEnter:
    case glfw::kKeyKpEnter:
      return 0x0D;
    case glfw::kKeyTab:
      return 0x09;
    case glfw::kKeyBackspace:
      return 0x08;
    case glfw::kKeyInsert:
      return 0x2D;
    case glfw::kKeyDelete:
      return 0x2E;
    case glfw::kKeyRight:
      return 0x27;
    case glfw::kKeyLeft:
      return 0x25;
    case glfw::kKeyDown:
      return 0x28;
    case glfw::kKeyUp:
      return 0x26;
    case glfw::kKeyPageUp:
      return 0x21;
    case glfw::kKeyPageDown:
      return 0x22;
    case glfw::kKeyHome:
      return 0x24;
    case glfw::kKeyEnd:
      return 0x23;
    case glfw::kKeyCapsLock:
      return 0x14;
    case glfw::kKeyScrollLock:
      return 0x91;
    case glfw::kKeyNumLock:
      return 0x90;
    case glfw::kKeyPrintScreen:
      return 0x2C;
    case glfw::kKeyPause:
      return 0x13;
    case glfw::kKeyKpDecimal:
      return 0x6E;
    case glfw::kKeyKpDivide:
      return 0x6F;
    case glfw::kKeyKpMultiply:
      return 0x6A;
    case glfw::kKeyKpSubtract:
      return 0x6D;
    case glfw::kKeyKpAdd:
      return 0x6B;
    case glfw::kKeyKpEqual:
      return 0xBB;
    case glfw::kKeyLeftShift:
    case glfw::kKeyRightShift:
      return 0x10;
    case glfw::kKeyLeftControl:
    case glfw::kKeyRightControl:
      return 0x11;
    case glfw::kKeyLeftAlt:
    case glfw::kKeyRightAlt:
      return 0x12;
    case glfw::kKeyLeftSuper:
      return 0x5B;
    case glfw::kKeyRightSuper:
      return 0x5C;
    case glfw::kKeyMenu:
      return 0x5D;
    default:
      return 0;
  }
}

int GetMacWindowsKeyCode(int key_code, int key_location, InputEventSemantics semantics) {
  return semantics == InputEventSemantics::kAwt
             ? GetAwtMacWindowsKeyCode(key_code, key_location)
             : GetGlfwMacWindowsKeyCode(key_code);
}

char16_t GetMacSpecialCharacter(int key_code, InputEventSemantics semantics) {
  int function_number = 0;
  if (semantics == InputEventSemantics::kAwt) {
    if (key_code >= awt::kVkF1 && key_code <= awt::kVkF12)
      function_number = 1 + key_code - awt::kVkF1;
    else if (key_code >= awt::kVkF13 && key_code <= awt::kVkF24)
      function_number = 13 + key_code - awt::kVkF13;
  } else if (key_code >= glfw::kKeyF1 && key_code <= glfw::kKeyF25) {
    function_number = 1 + key_code - glfw::kKeyF1;
  }
  if (function_number != 0)
    return 0xF704 + function_number - 1;
  if (semantics == InputEventSemantics::kAwt) {
    switch (key_code) {
      case awt::kVkKpUp:
        return 0xF700;
      case awt::kVkKpDown:
        return 0xF701;
      case awt::kVkKpLeft:
        return 0xF702;
      case awt::kVkKpRight:
        return 0xF703;
      case awt::kVkInsert:
        return 0xF727;
      case awt::kVkHelp:
        return 0xF746;
      case awt::kVkPrintScreen:
        return 0xF72E;
      case awt::kVkScrollLock:
        return 0xF72F;
      case awt::kVkPause:
        return 0xF730;
      case awt::kVkClear:
        return 0xF739;
      default:
        break;
    }
  } else {
    switch (key_code) {
      case glfw::kKeyInsert:
        return 0xF727;
      case glfw::kKeyPrintScreen:
        return 0xF72E;
      case glfw::kKeyScrollLock:
        return 0xF72F;
      case glfw::kKeyPause:
        return 0xF730;
      default:
        break;
    }
  }

  const int backspace = semantics == InputEventSemantics::kAwt
                            ? awt::kVkBackSpace
                            : glfw::kKeyBackspace;
  const int delete_key = semantics == InputEventSemantics::kAwt
                             ? awt::kVkDelete
                             : glfw::kKeyDelete;
  const int down =
      semantics == InputEventSemantics::kAwt ? awt::kVkDown : glfw::kKeyDown;
  const int enter =
      semantics == InputEventSemantics::kAwt ? awt::kVkEnter : glfw::kKeyEnter;
  const int escape = semantics == InputEventSemantics::kAwt ? awt::kVkEscape
                                                            : glfw::kKeyEscape;
  const int home =
      semantics == InputEventSemantics::kAwt ? awt::kVkHome : glfw::kKeyHome;
  const int end =
      semantics == InputEventSemantics::kAwt ? awt::kVkEnd : glfw::kKeyEnd;
  const int left =
      semantics == InputEventSemantics::kAwt ? awt::kVkLeft : glfw::kKeyLeft;
  const int page_up = semantics == InputEventSemantics::kAwt ? awt::kVkPageUp
                                                             : glfw::kKeyPageUp;
  const int page_down = semantics == InputEventSemantics::kAwt
                            ? awt::kVkPageDown
                            : glfw::kKeyPageDown;
  const int right =
      semantics == InputEventSemantics::kAwt ? awt::kVkRight : glfw::kKeyRight;
  const int tab =
      semantics == InputEventSemantics::kAwt ? awt::kVkTab : glfw::kKeyTab;
  const int up =
      semantics == InputEventSemantics::kAwt ? awt::kVkUp : glfw::kKeyUp;
  if (key_code == backspace)
    return kBackspaceCharCode;
  if (key_code == delete_key)
    return 0xF728;
  if (key_code == down)
    return 0xF701;
  if (key_code == enter)
    return kReturnCharCode;
  if (key_code == escape)
    return kEscapeCharCode;
  if (key_code == home)
    return 0xF729;
  if (key_code == end)
    return 0xF72B;
  if (key_code == left)
    return 0xF702;
  if (key_code == page_up)
    return 0xF72C;
  if (key_code == page_down)
    return 0xF72D;
  if (key_code == right)
    return 0xF703;
  if (key_code == tab)
    return kTabCharCode;
  if (key_code == up)
    return 0xF700;
  return 0;
}
#endif  // defined(OS_MACOSX)

struct KeyEventConstants {
  int pressed;
  int released;
  int typed;
  int repeated;
};

constexpr KeyEventConstants kAwtKeyEventConstants = {
    awt::kKeyPressed, awt::kKeyReleased, awt::kKeyTyped, -1};

constexpr KeyEventConstants kGlfwKeyEventConstants = {
    glfw::kPress, glfw::kRelease, glfw::kTyped, glfw::kExplicitRepeat};

bool CallRequiredIntMethod(JNIEnv* env, jclass cls, jobject obj, const char* method_name, int* value) {
  jmethodID method = env->GetMethodID(cls, method_name, "()I");
  if (!method)
    return false;
  *value = env->CallIntMethod(obj, method);
  return !env->ExceptionCheck();
}

bool CallRequiredCharMethod(JNIEnv* env, jclass cls, jobject obj, const char* method_name, char16_t* value) {
  jmethodID method = env->GetMethodID(cls, method_name, "()C");
  if (!method)
    return false;
  *value = env->CallCharMethod(obj, method);
  return !env->ExceptionCheck();
}

bool CallRequiredDoubleMethod(JNIEnv* env, jclass cls, jobject obj, const char* method_name, double* value) {
  jmethodID method = env->GetMethodID(cls, method_name, "()D");
  if (!method)
    return false;
  *value = env->CallDoubleMethod(obj, method);
  return !env->ExceptionCheck();
}

#if defined(OS_WIN)
UINT GetWindowsVirtualKeyFromAwt(int key_code, int key_location) {
  if ((key_code >= '0' && key_code <= '9') ||
      (key_code >= 'A' && key_code <= 'Z') ||
      (key_code >= awt::kVkF1 && key_code <= awt::kVkF12) ||
      (key_code >= awt::kVkNumpad0 && key_code <= awt::kVkNumpad9)) {
    return static_cast<UINT>(key_code);
  }
  if (key_code >= awt::kVkF13 && key_code <= awt::kVkF24)
    return VK_F13 + key_code - awt::kVkF13;

  switch (key_code) {
    case awt::kVkCancel:
      return VK_CANCEL;
    case awt::kVkBackSpace:
      return VK_BACK;
    case awt::kVkTab:
      return VK_TAB;
    case awt::kVkEnter:
      return VK_RETURN;
    case awt::kVkClear:
      return VK_CLEAR;
    case awt::kVkShift:
      return key_location == awt::kKeyLocationLeft    ? VK_LSHIFT
             : key_location == awt::kKeyLocationRight ? VK_RSHIFT
                                                      : VK_SHIFT;
    case awt::kVkControl:
      return key_location == awt::kKeyLocationLeft    ? VK_LCONTROL
             : key_location == awt::kKeyLocationRight ? VK_RCONTROL
                                                      : VK_CONTROL;
    case awt::kVkAlt:
      return key_location == awt::kKeyLocationLeft    ? VK_LMENU
             : key_location == awt::kKeyLocationRight ? VK_RMENU
                                                      : VK_MENU;
    case awt::kVkPause:
      return VK_PAUSE;
    case awt::kVkCapsLock:
      return VK_CAPITAL;
    case awt::kVkEscape:
      return VK_ESCAPE;
    case awt::kVkSpace:
      return VK_SPACE;
    case awt::kVkPageUp:
      return VK_PRIOR;
    case awt::kVkPageDown:
      return VK_NEXT;
    case awt::kVkEnd:
      return VK_END;
    case awt::kVkHome:
      return VK_HOME;
    case awt::kVkLeft:
      return VK_LEFT;
    case awt::kVkUp:
      return VK_UP;
    case awt::kVkRight:
      return VK_RIGHT;
    case awt::kVkDown:
      return VK_DOWN;
    case awt::kVkMultiply:
      return VK_MULTIPLY;
    case awt::kVkAdd:
      return VK_ADD;
    case awt::kVkSeparator:
      return VK_SEPARATOR;
    case awt::kVkSubtract:
      return VK_SUBTRACT;
    case awt::kVkDecimal:
      return VK_DECIMAL;
    case awt::kVkDivide:
      return VK_DIVIDE;
    case awt::kVkDelete:
      return VK_DELETE;
    case awt::kVkDeadGrave:
    case awt::kVkDeadTilde:
      return VK_OEM_3;
    case awt::kVkDeadAcute:
    case awt::kVkDeadDoubleAcute:
    case awt::kVkDeadVoicedSound:
    case awt::kVkDeadSemivoicedSound:
      return VK_OEM_7;
    case awt::kVkDeadCircumflex:
      return '6';
    case awt::kVkDeadMacron:
      return VK_OEM_MINUS;
    case awt::kVkDeadBreve:
      return 'B';
    case awt::kVkDeadAboveDot:
      return VK_OEM_PERIOD;
    case awt::kVkDeadDiaeresis:
      return 'U';
    case awt::kVkDeadAboveRing:
      return 'A';
    case awt::kVkDeadCaron:
    case awt::kVkDeadCedilla:
      return 'C';
    case awt::kVkDeadOgonek:
      return 'O';
    case awt::kVkDeadIota:
      return 'I';
    case awt::kVkNumLock:
      return VK_NUMLOCK;
    case awt::kVkScrollLock:
      return VK_SCROLL;
    case awt::kVkPrintScreen:
      return VK_SNAPSHOT;
    case awt::kVkInsert:
      return VK_INSERT;
    case awt::kVkHelp:
      return VK_HELP;
    case awt::kVkMeta:
    case awt::kVkWindows:
      return key_location == awt::kKeyLocationRight ? VK_RWIN : VK_LWIN;
    case awt::kVkKpUp:
      return VK_UP;
    case awt::kVkKpDown:
      return VK_DOWN;
    case awt::kVkKpLeft:
      return VK_LEFT;
    case awt::kVkKpRight:
      return VK_RIGHT;
    case awt::kVkContextMenu:
    case awt::kVkCompose:
      return VK_APPS;
    case awt::kVkAltGraph:
      return VK_RMENU;
    case 44:  // KeyEvent.VK_COMMA
      return VK_OEM_COMMA;
    case 45:  // KeyEvent.VK_MINUS
      return VK_OEM_MINUS;
    case 46:  // KeyEvent.VK_PERIOD
      return VK_OEM_PERIOD;
    case 47:  // KeyEvent.VK_SLASH
      return VK_OEM_2;
    case 59:  // KeyEvent.VK_SEMICOLON
      return VK_OEM_1;
    case 61:  // KeyEvent.VK_EQUALS
      return VK_OEM_PLUS;
    case 91:  // KeyEvent.VK_OPEN_BRACKET
      return VK_OEM_4;
    case 92:  // KeyEvent.VK_BACK_SLASH
      return VK_OEM_5;
    case 93:  // KeyEvent.VK_CLOSE_BRACKET
      return VK_OEM_6;
    case 192:  // KeyEvent.VK_BACK_QUOTE
      return VK_OEM_3;
    case 222:  // KeyEvent.VK_QUOTE
      return VK_OEM_7;
    default:
      return 0;
  }
}

UINT GetWindowsVirtualKeyFromGlfw(int key_code) {
  if ((key_code >= '0' && key_code <= '9') ||
      (key_code >= 'A' && key_code <= 'Z')) {
    return static_cast<UINT>(key_code);
  }
  if (key_code >= glfw::kKeyF1 && key_code < glfw::kKeyF25)
    return VK_F1 + key_code - glfw::kKeyF1;
  if (key_code >= glfw::kKeyKp0 && key_code <= glfw::kKeyKp9)
    return VK_NUMPAD0 + key_code - glfw::kKeyKp0;

  switch (key_code) {
    case 32:  // GLFW_KEY_SPACE
      return VK_SPACE;
    case 39:  // GLFW_KEY_APOSTROPHE
      return VK_OEM_7;
    case 44:  // GLFW_KEY_COMMA
      return VK_OEM_COMMA;
    case 45:  // GLFW_KEY_MINUS
      return VK_OEM_MINUS;
    case 46:  // GLFW_KEY_PERIOD
      return VK_OEM_PERIOD;
    case 47:  // GLFW_KEY_SLASH
      return VK_OEM_2;
    case 59:  // GLFW_KEY_SEMICOLON
      return VK_OEM_1;
    case 61:  // GLFW_KEY_EQUAL
      return VK_OEM_PLUS;
    case 91:  // GLFW_KEY_LEFT_BRACKET
      return VK_OEM_4;
    case 92:  // GLFW_KEY_BACKSLASH
      return VK_OEM_5;
    case 93:  // GLFW_KEY_RIGHT_BRACKET
      return VK_OEM_6;
    case 96:  // GLFW_KEY_GRAVE_ACCENT
      return VK_OEM_3;
    case glfw::kKeyEscape:
      return VK_ESCAPE;
    case glfw::kKeyEnter:
      return VK_RETURN;
    case glfw::kKeyTab:
      return VK_TAB;
    case glfw::kKeyBackspace:
      return VK_BACK;
    case glfw::kKeyInsert:
      return VK_INSERT;
    case glfw::kKeyDelete:
      return VK_DELETE;
    case glfw::kKeyRight:
      return VK_RIGHT;
    case glfw::kKeyLeft:
      return VK_LEFT;
    case glfw::kKeyDown:
      return VK_DOWN;
    case glfw::kKeyUp:
      return VK_UP;
    case glfw::kKeyPageUp:
      return VK_PRIOR;
    case glfw::kKeyPageDown:
      return VK_NEXT;
    case glfw::kKeyHome:
      return VK_HOME;
    case glfw::kKeyEnd:
      return VK_END;
    case glfw::kKeyCapsLock:
      return VK_CAPITAL;
    case glfw::kKeyScrollLock:
      return VK_SCROLL;
    case glfw::kKeyNumLock:
      return VK_NUMLOCK;
    case glfw::kKeyPrintScreen:
      return VK_SNAPSHOT;
    case glfw::kKeyPause:
      return VK_PAUSE;
    case glfw::kKeyKpDecimal:
      return VK_DECIMAL;
    case glfw::kKeyKpDivide:
      return VK_DIVIDE;
    case glfw::kKeyKpMultiply:
      return VK_MULTIPLY;
    case glfw::kKeyKpSubtract:
      return VK_SUBTRACT;
    case glfw::kKeyKpAdd:
      return VK_ADD;
    case glfw::kKeyKpEnter:
      return VK_RETURN;
    case glfw::kKeyKpEqual:
      return VK_OEM_PLUS;
    case glfw::kKeyLeftShift:
      return VK_LSHIFT;
    case glfw::kKeyRightShift:
      return VK_RSHIFT;
    case glfw::kKeyLeftControl:
      return VK_LCONTROL;
    case glfw::kKeyRightControl:
      return VK_RCONTROL;
    case glfw::kKeyLeftAlt:
      return VK_LMENU;
    case glfw::kKeyRightAlt:
      return VK_RMENU;
    case glfw::kKeyLeftSuper:
      return VK_LWIN;
    case glfw::kKeyRightSuper:
      return VK_RWIN;
    case glfw::kKeyMenu:
      return VK_APPS;
    default:
      return 0;
  }
}

UINT GetWindowsVirtualKey(int key_code, int key_location, InputEventSemantics semantics) {
  return semantics == InputEventSemantics::kAwt
             ? GetWindowsVirtualKeyFromAwt(key_code, key_location)
             : GetWindowsVirtualKeyFromGlfw(key_code);
}

bool IsWindowsExtendedKey(int key_code, int key_location, UINT virtual_key, UINT scan_code, InputEventSemantics semantics) {
  // Synthetic AWT keypad navigation events can acquire an E0 scan prefix from
  // MapVirtualKey even though their NUMPAD location requires the non-extended
  // keypad interpretation. Resolve that location before inspecting the scan.
  if (semantics == InputEventSemantics::kAwt &&
      key_location == awt::kKeyLocationNumpad)
    return key_code == awt::kVkEnter || key_code == awt::kVkDivide ||
           key_code == awt::kVkNumLock;
  if ((scan_code & 0xFF00U) == 0x0100U || (scan_code & 0xFF00U) == 0xE000U || (scan_code & 0xFF00U) == 0xE100U)
    return true;
  if (semantics == InputEventSemantics::kGlfw &&
      (key_code == glfw::kKeyKpEnter || key_code == glfw::kKeyKpDivide))
    return true;
  switch (virtual_key) {
    case VK_RCONTROL:
    case VK_RMENU:
    case VK_INSERT:
    case VK_DELETE:
    case VK_HOME:
    case VK_END:
    case VK_PRIOR:
    case VK_NEXT:
    case VK_LEFT:
    case VK_RIGHT:
    case VK_UP:
    case VK_DOWN:
    case VK_NUMLOCK:
    case VK_SNAPSHOT:
    case VK_LWIN:
    case VK_RWIN:
    case VK_APPS:
      return true;
    default:
      return false;
  }
}

char16_t GetWindowsSpecialCharacter(UINT virtual_key) {
  switch (virtual_key) {
    case VK_RETURN:
      return '\r';
    case VK_BACK:
      return '\b';
    case VK_TAB:
      return '\t';
    case VK_ESCAPE:
      return 0x1B;
    case VK_DELETE:
      return 0x7F;
    default:
      return 0;
  }
}

UINT GetWindowsKeyCodeWithoutLocation(UINT virtual_key) {
  switch (virtual_key) {
    case VK_LSHIFT:
    case VK_RSHIFT:
      return VK_SHIFT;
    case VK_LCONTROL:
    case VK_RCONTROL:
      return VK_CONTROL;
    case VK_LMENU:
    case VK_RMENU:
      return VK_MENU;
    default:
      return virtual_key;
  }
}

#endif  // defined(OS_WIN)

void SendJavaKeyEvent(JNIEnv* env, CefRefPtr<CefBrowser> browser, jobject key_event, InputEventSemantics semantics, bool awt_repeated) {
  if (!key_event)
    return;
  ScopedJNIClass event_class(env, env->GetObjectClass(key_event));
  if (!event_class)
    return;

  int event_type = 0;
  int key_code = 0;
  int key_location = awt::kKeyLocationUnknown;
  int modifiers = 0;
  char16_t key_char = awt::kCharUndefined;
  const char* modifiers_method = semantics == InputEventSemantics::kAwt
                                     ? "getModifiersEx"
                                     : "getModifiers";
  if (!CallRequiredIntMethod(env, event_class, key_event, "getID", &event_type) ||
      !CallRequiredIntMethod(env, event_class, key_event, "getKeyCode", &key_code) ||
      !CallRequiredCharMethod(env, event_class, key_event, "getKeyChar", &key_char) ||
      !CallRequiredIntMethod(env, event_class, key_event, modifiers_method, &modifiers)) {
    return;
  }
  if (semantics == InputEventSemantics::kAwt &&
      !CallRequiredIntMethod(env, event_class, key_event, "getKeyLocation", &key_location))
    return;

  const KeyEventConstants& constants = semantics == InputEventSemantics::kAwt
                                           ? kAwtKeyEventConstants
                                           : kGlfwKeyEventConstants;
  const bool pressed = event_type == constants.pressed;
  const bool released = event_type == constants.released;
  const bool typed = event_type == constants.typed;
  const bool explicit_repeat = event_type == constants.repeated;
  const bool repeated =
      explicit_repeat || (semantics == InputEventSemantics::kAwt &&
                          awt_repeated && (pressed || typed));
  if (!pressed && !released && !typed && !explicit_repeat)
    return;
  if (typed && !HasDefinedKeyChar(key_char))
    return;

  const bool glfw_raw = !typed && semantics == InputEventSemantics::kGlfw;
  const bool glfw_shift = glfw_raw && (modifiers & glfw::kModShift) != 0;
  const bool glfw_caps_lock = glfw_raw && (modifiers & glfw::kModCapsLock) != 0;
  const char16_t glfw_raw_character =
      glfw_raw
          ? GetGlfwRawCharacter(key_code, key_char, glfw_shift, glfw_caps_lock)
          : awt::kCharUndefined;

  jlong primary_level_unicode = 0;
  // OpenJDK populates this private Shift-neutral layout character on
  // Windows/X11, but synthetic and macOS events normally leave it zero. Keep
  // the lookup optional and retain physical fallbacks.
  if (!typed && semantics == InputEventSemantics::kAwt)
    GetJNIFieldLong(env, event_class, key_event, "primaryLevelUnicode", &primary_level_unicode);
  char16_t identity_key_char = typed ||
                                       semantics == InputEventSemantics::kAwt ||
                                       IsGlfwStandardPrintableKey(key_code)
                                   ? key_char
                                   : awt::kCharUndefined;
  if (glfw_raw && key_code >= 'A' && key_code <= 'Z')
    identity_key_char = glfw_raw_character;
  if (!typed && semantics == InputEventSemantics::kAwt &&
      !IsPrintableKeyChar(identity_key_char) &&
      IsValidBmpCharacter(primary_level_unicode))
    identity_key_char = static_cast<char16_t>(primary_level_unicode);

  CefKeyEvent cef_event;
  cef_event.modifiers = GetCefKeyModifiers(modifiers, semantics);
  if (!typed)
    cef_event.modifiers |=
        GetKeyIdentityModifiers(key_code, key_location, semantics);
  if (repeated)
    cef_event.modifiers |= EVENTFLAG_IS_REPEAT;
  // AWT exposes no repeat flag, so Java infers it from the pressed-key
  // lifecycle and supplies it separately. The legacy DTO retains its explicit
  // KEY_REPEAT action and ABI.

  char16_t platform_special_character = awt::kCharUndefined;
  int control_windows_key_code = 0;

#if defined(OS_WIN)
  jlong supplied_scan_code = 0;
  jlong supplied_raw_code = 0;
  // Java's private AWT field is an optional optimization and CefKeyEvent's
  // public field may be zero for synthetic MCEF input. GetJNIFieldLong clears
  // only the optional lookup failure; required getter failures above propagate.
  GetJNIFieldLong(env, event_class, key_event, "scancode", &supplied_scan_code);
  if (!typed && semantics == InputEventSemantics::kAwt)
    GetJNIFieldLong(env, event_class, key_event, "rawCode", &supplied_raw_code);
  const UINT fallback_virtual_key =
      typed ? 0 : GetWindowsVirtualKey(key_code, key_location, semantics);
  const bool has_raw_code = supplied_raw_code > 0 && supplied_raw_code != 0xFF;
  UINT virtual_key = has_raw_code ? static_cast<UINT>(supplied_raw_code)
                                  : fallback_virtual_key;
  if (fallback_virtual_key == VK_LSHIFT || fallback_virtual_key == VK_RSHIFT ||
      fallback_virtual_key == VK_LCONTROL ||
      fallback_virtual_key == VK_RCONTROL || fallback_virtual_key == VK_LMENU ||
      fallback_virtual_key == VK_RMENU || fallback_virtual_key == VK_LWIN ||
      fallback_virtual_key == VK_RWIN)
    virtual_key = fallback_virtual_key;
  UINT scan_code =
      supplied_scan_code > 0 ? static_cast<UINT>(supplied_scan_code) : 0;
  SHORT typed_virtual_key = -1;
  if (typed) {
    typed_virtual_key =
        VkKeyScanExW(static_cast<WCHAR>(key_char), GetKeyboardLayout(0));
    if (typed_virtual_key != -1)
      virtual_key = LOBYTE(typed_virtual_key);
  }
  if (typed_virtual_key != -1 &&
      ((static_cast<unsigned int>(typed_virtual_key) >> 8) & 0x06U) == 0x06U) {
    cef_event.modifiers &= ~(EVENTFLAG_CONTROL_DOWN | EVENTFLAG_ALT_DOWN);
    cef_event.modifiers |= EVENTFLAG_ALTGR_DOWN;
  }
  if (scan_code == 0 && virtual_key != 0)
    scan_code = MapVirtualKey(virtual_key, MAPVK_VK_TO_VSC_EX);
  if (!typed && virtual_key == 0 && scan_code != 0)
    virtual_key = MapVirtualKey(scan_code, MAPVK_VSC_TO_VK_EX);
  const bool extended = IsWindowsExtendedKey(typed ? -1 : key_code, typed ? awt::kKeyLocationUnknown : key_location, virtual_key, scan_code, semantics);
  // CEF 151 passes this value directly to Chromium's physical-key converter,
  // which expects an OEM scan code rather than a packed WM_KEY* lParam. Event
  // type, Alt state and repetition are already represented by dedicated CEF
  // fields and must not be duplicated as lParam bits here.
  cef_event.native_key_code = key_event_platform_util::ResolveWindowsNativeKeyCode(supplied_scan_code, scan_code, extended);
  cef_event.is_system_key = (cef_event.modifiers & EVENTFLAG_ALT_DOWN) != 0 ||
                            virtual_key == VK_MENU || virtual_key == VK_LMENU ||
                            virtual_key == VK_RMENU;
  const char16_t windows_special_character =
      GetWindowsSpecialCharacter(virtual_key);
  if (windows_special_character != 0)
    platform_special_character = windows_special_character;
  control_windows_key_code = virtual_key;
#elif defined(OS_LINUX)
  const unsigned int key_sym = GetLinuxKeySym(key_code, key_location, identity_key_char, typed, semantics);
  if (key_sym == NoSymbol)
    return;
  jlong supplied_native_key_code = 0;
  GetJNIFieldLong(env, event_class, key_event, semantics == InputEventSemantics::kAwt ? "rawCode" : "scancode", &supplied_native_key_code);
  cef_event.native_key_code = key_event_platform_util::ResolveLinuxNativeKeyCode(supplied_native_key_code, key_code, key_location, typed, semantics);
  KeyboardCode windows_key_code = KeyboardCodeFromXKeysym(key_sym);
  cef_event.windows_key_code =
      GetWindowsKeyCodeWithoutLocation(windows_key_code);
  if (windows_key_code == VKEY_RETURN)
    platform_special_character = '\r';
  else if (windows_key_code == VKEY_BACK)
    platform_special_character = '\b';
  else if (windows_key_code == VKEY_TAB)
    platform_special_character = '\t';
  else if (windows_key_code == VKEY_ESCAPE)
    platform_special_character = 0x1B;
  else if (windows_key_code == VKEY_DELETE)
    platform_special_character = 0x7F;
  control_windows_key_code = windows_key_code;
#elif defined(OS_MACOSX)
  jlong supplied_scan_code = 0;
  if (semantics == InputEventSemantics::kGlfw)
    GetJNIFieldLong(env, event_class, key_event, "scancode", &supplied_scan_code);
  const int fallback_key_code = GetMacKeyCode(key_code, key_location, identity_key_char, typed, semantics);
  const int windows_key_code =
      typed ? key_char
            : GetMacWindowsKeyCode(key_code, key_location, semantics);
  const char16_t special_character =
      GetMacSpecialCharacter(key_code, semantics);
  const bool has_unicode = HasDefinedKeyChar(identity_key_char);
  if (supplied_scan_code == 0 && fallback_key_code == -1 &&
      windows_key_code == 0 && special_character == 0 && !has_unicode)
    return;
  cef_event.native_key_code = supplied_scan_code != 0
                                  ? static_cast<int>(supplied_scan_code)
                              : fallback_key_code == -1 ? 0
                                                        : fallback_key_code;
  cef_event.windows_key_code = windows_key_code;
  if (special_character != 0)
    platform_special_character = special_character;
  control_windows_key_code = windows_key_code;
#endif

  const bool shift = (cef_event.modifiers & EVENTFLAG_SHIFT_DOWN) != 0;
  char16_t unmodified_character = awt::kCharUndefined;
  if (typed) {
    unmodified_character = key_char;
  } else if (semantics == InputEventSemantics::kAwt) {
    char16_t physical_fallback =
        GetAwtPhysicalUnmodifiedCharacter(key_code, shift);
    if (HasDefinedKeyChar(platform_special_character))
      physical_fallback = platform_special_character;
    unmodified_character = GetAwtUnmodifiedCharacter(env, key_char, modifiers, primary_level_unicode, physical_fallback);
  } else {
    unmodified_character =
        HasDefinedKeyChar(platform_special_character)
            ? platform_special_character
            : GetGlfwRawUnmodifiedCharacter(key_code, key_char, shift);
  }
  cef_event.unmodified_character =
      HasDefinedKeyChar(unmodified_character) ? unmodified_character : 0;
  if (typed ||
      (semantics == InputEventSemantics::kAwt && HasDefinedKeyChar(key_char))) {
    cef_event.character = key_char;
  } else if (semantics == InputEventSemantics::kGlfw) {
    cef_event.character = HasDefinedKeyChar(glfw_raw_character)
                              ? glfw_raw_character
                              : cef_event.unmodified_character;
  } else {
    cef_event.character = cef_event.unmodified_character;
  }
  if (!typed && (pressed || explicit_repeat) &&
      (cef_event.modifiers & EVENTFLAG_CONTROL_DOWN))
    cef_event.character =
        GetControlCharacterFromWindowsKeyCode(control_windows_key_code, shift);

  if (pressed || explicit_repeat) {
#if defined(OS_WIN)
    cef_event.windows_key_code = GetWindowsKeyCodeWithoutLocation(virtual_key);
#endif
    cef_event.type = KEYEVENT_RAWKEYDOWN;
  } else if (released) {
#if defined(OS_WIN)
    cef_event.windows_key_code = GetWindowsKeyCodeWithoutLocation(virtual_key);
#endif
    cef_event.type = KEYEVENT_KEYUP;
  } else if (typed) {
#if defined(OS_WIN)
    cef_event.windows_key_code = key_char;
#endif
    cef_event.type = KEYEVENT_CHAR;
  }

  if (env->ExceptionCheck())
    return;
  browser->GetHost()->SendKeyEvent(cef_event);
}

void SendJavaMouseEvent(JNIEnv* env, CefRefPtr<CefBrowser> browser, jobject mouse_event, InputEventSemantics semantics) {
  if (!mouse_event)
    return;
  ScopedJNIClass event_class(env, env->GetObjectClass(mouse_event));
  if (!event_class)
    return;

  int event_type = 0;
  int x = 0;
  int y = 0;
  int modifiers = 0;
  const char* modifiers_method = semantics == InputEventSemantics::kAwt
                                     ? "getModifiersEx"
                                     : "getModifiers";
  if (!CallRequiredIntMethod(env, event_class, mouse_event, "getID", &event_type) ||
      !CallRequiredIntMethod(env, event_class, mouse_event, "getX", &x) ||
      !CallRequiredIntMethod(env, event_class, mouse_event, "getY", &y) ||
      !CallRequiredIntMethod(env, event_class, mouse_event, modifiers_method, &modifiers)) {
    return;
  }

  CefMouseEvent cef_event;
  cef_event.x = x;
  cef_event.y = y;
  cef_event.modifiers = GetCefPointerModifiers(modifiers, semantics);

  const int pressed = semantics == InputEventSemantics::kAwt
                          ? awt::kMousePressed
                          : glfw::kPress;
  const int released = semantics == InputEventSemantics::kAwt
                           ? awt::kMouseReleased
                           : glfw::kRelease;
  if (event_type == pressed || event_type == released) {
    int button = 0;
    int click_count = 0;
    if (!CallRequiredIntMethod(env, event_class, mouse_event, "getClickCount", &click_count) ||
        !CallRequiredIntMethod(env, event_class, mouse_event, "getButton", &button)) {
      return;
    }

    CefBrowserHost::MouseButtonType cef_button;
    if (button == (semantics == InputEventSemantics::kAwt
                       ? awt::kButton1
                       : glfw::kMouseButton1)) {
      cef_button = MBT_LEFT;
    } else if (button == (semantics == InputEventSemantics::kAwt
                              ? awt::kButton2
                              : glfw::kMouseButton2)) {
      cef_button = MBT_MIDDLE;
    } else if (button == (semantics == InputEventSemantics::kAwt
                              ? awt::kButton3
                              : glfw::kMouseButton3)) {
      cef_button = MBT_RIGHT;
    } else {
      return;
    }
    if (env->ExceptionCheck())
      return;
    browser->GetHost()->SendMouseClickEvent(cef_event, cef_button, event_type == released, click_count);
    return;
  }

  const int moved = semantics == InputEventSemantics::kAwt ? awt::kMouseMoved
                                                           : glfw::kMouseMoved;
  const int dragged = semantics == InputEventSemantics::kAwt
                          ? awt::kMouseDragged
                          : glfw::kMouseDragged;
  const int entered = semantics == InputEventSemantics::kAwt
                          ? awt::kMouseEntered
                          : glfw::kMouseEntered;
  const int exited = semantics == InputEventSemantics::kAwt
                         ? awt::kMouseExited
                         : glfw::kMouseExited;
  if (event_type == moved || event_type == dragged || event_type == entered ||
      event_type == exited) {
    if (env->ExceptionCheck())
      return;
    browser->GetHost()->SendMouseMoveEvent(cef_event, event_type == exited);
  }
}

void SendJavaMouseWheelEvent(JNIEnv* env, CefRefPtr<CefBrowser> browser, jobject mouse_wheel_event, InputEventSemantics semantics) {
  if (!mouse_wheel_event)
    return;
  ScopedJNIClass event_class(env, env->GetObjectClass(mouse_wheel_event));
  if (!event_class)
    return;

  int scroll_type = 0;
  int x = 0;
  int y = 0;
  int modifiers = 0;
  const char* modifiers_method = semantics == InputEventSemantics::kAwt
                                     ? "getModifiersEx"
                                     : "getModifiers";
  if (!CallRequiredIntMethod(env, event_class, mouse_wheel_event, "getScrollType", &scroll_type) ||
      !CallRequiredIntMethod(env, event_class, mouse_wheel_event, "getX", &x) ||
      !CallRequiredIntMethod(env, event_class, mouse_wheel_event, "getY", &y) ||
      !CallRequiredIntMethod(env, event_class, mouse_wheel_event, modifiers_method, &modifiers)) {
    return;
  }

  CefMouseEvent cef_event;
  cef_event.x = x;
  cef_event.y = y;
  cef_event.modifiers = GetCefPointerModifiers(modifiers, semantics);

  if (semantics == InputEventSemantics::kGlfw) {
    double delta = 0;
    if (!CallRequiredDoubleMethod(env, event_class, mouse_wheel_event, "getWheelRotation", &delta))
      return;
    if (scroll_type == glfw::kWheelUnitScroll &&
        !CallRequiredDoubleMethod(env, event_class, mouse_wheel_event, "getUnitsToScroll", &delta))
      return;
    double delta_x = 0;
    double delta_y = 0;
    if (cef_event.modifiers & EVENTFLAG_SHIFT_DOWN)
      delta_x = delta;
    else
      delta_y = delta;
    if (env->ExceptionCheck())
      return;
    // Keep the historical DTO direction, scale and C++ double-to-int conversion
    // unchanged.
    browser->GetHost()->SendMouseWheelEvent(cef_event, delta_x, delta_y);
    return;
  }

  double precise_rotation = 0;
  if (!CallRequiredDoubleMethod(env, event_class, mouse_wheel_event, "getPreciseWheelRotation", &precise_rotation) ||
      !std::isfinite(precise_rotation))
    return;

#if defined(OS_WIN)
  int target_delta = 0;
#else
  int delta = 0;
#endif
  if (scroll_type == awt::kWheelUnitScroll) {
#if defined(OS_WIN)
    int scroll_amount = 0;
    if (!CallRequiredIntMethod(env, event_class, mouse_wheel_event, "getScrollAmount", &scroll_amount) || scroll_amount < 0)
      return;
    // AWT's precise unit distance is preciseWheelRotation * scrollAmount. CEF
    // 151 instead expects a raw Windows tick and reapplies the current system
    // setting, so preserve the AWT event as the source of truth and invert that
    // platform transform below.
    target_delta = mouse_wheel_platform_util::GetWindowsAwtUnitTargetDelta(precise_rotation, scroll_amount);
#else
    // CEF's Linux and macOS sample clients use 40 device units for one wheel
    // tick.
    constexpr double kWheelUnitScale = 40.0;
    delta = mouse_wheel_platform_util::RoundNonZeroWheelDelta(-precise_rotation * kWheelUnitScale);
#endif
    if (precise_rotation != std::trunc(precise_rotation))
      cef_event.modifiers |= EVENTFLAG_PRECISION_SCROLLING_DELTA;
  } else if (scroll_type == awt::kWheelBlockScroll) {
#if defined(OS_WIN)
    target_delta = mouse_wheel_platform_util::RoundNonZeroWheelDelta(-precise_rotation);
#else
    delta = mouse_wheel_platform_util::RoundNonZeroWheelDelta(-precise_rotation);
#endif
    cef_event.modifiers |= EVENTFLAG_SCROLL_BY_PAGE;
  } else {
    return;
  }

  const bool horizontal = (cef_event.modifiers & EVENTFLAG_SHIFT_DOWN) != 0;
#if defined(OS_WIN)
  if (target_delta == 0)
    return;
  // The bundled CEF binary applies line/character scaling even to page events.
  // Quantized magnitudes use the nearest safe same-sign result and are logged;
  // zero and WHEEL_PAGESCROLL settings cannot safely deliver a representative
  // event because WHEEL_PAGESCROLL can overflow gfx::Vector2d.
  if (env->ExceptionCheck())
    return;
  SendWindowsAwtMouseWheelEvent(browser, cef_event, target_delta, horizontal);
  return;
#else
  const int delta_x = horizontal ? delta : 0;
  const int delta_y = horizontal ? 0 : delta;
  if (env->ExceptionCheck())
    return;
  browser->GetHost()->SendMouseWheelEvent(cef_event, delta_x, delta_y);
#endif
}

struct JNIObjectsForCreate {
 public:
  ScopedJNIObjectGlobal jbrowser;
  ScopedJNIObjectGlobal jparentBrowser;
  ScopedJNIObjectGlobal jclientHandler;
  ScopedJNIObjectGlobal url;
  ScopedJNIObjectGlobal canvas;
  ScopedJNIObjectGlobal jcontext;
  ScopedJNIObjectGlobal jinspectAt;
  ScopedJNIObjectGlobal jbrowserSettings;

  JNIObjectsForCreate(JNIEnv* env,
                      jobject _jbrowser,
                      jobject _jparentBrowser,
                      jobject _jclientHandler,
                      jstring _url,
                      jobject _canvas,
                      jobject _jcontext,
                      jobject _jinspectAt,
                      jobject _browserSettings)
      :

        jbrowser(env, _jbrowser),
        jparentBrowser(env, _jparentBrowser),
        jclientHandler(env, _jclientHandler),
        url(env, _url),
        canvas(env, _canvas),
        jcontext(env, _jcontext),
        jinspectAt(env, _jinspectAt),
        jbrowserSettings(env, _browserSettings) {}
};

void NotifyBrowserCreationFailed(JNIEnv* env, jobject jbrowser) {
  JNI_CALL_VOID_METHOD(env, jbrowser, "notifyBrowserCreationFailed", "()V");
}

void LogAndClearBrowserSettingsFailure(JNIEnv* env, const std::string& error) {
  LOG(ERROR) << "Browser creation rejected invalid CefBrowserSettings: "
             << error;
  // Conversion can run later on CEF's UI thread. Never let its Java exception
  // suppress the lifecycle callback that releases the pending creation state.
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
}

void create(std::shared_ptr<JNIObjectsForCreate> objs,
            jlong windowHandle,
            jboolean osr,
            jboolean transparent) {
  ScopedJNIEnv env;
  if (!env)
    return;

  CefRefPtr<ClientHandler> clientHandler = GetCefFromJNIObject<ClientHandler>(
      env, objs->jclientHandler, "CefClientHandler");
  if (!clientHandler.get()) {
    NotifyBrowserCreationFailed(env, objs->jbrowser);
    return;
  }

  CefRefPtr<LifeSpanHandler> lifeSpanHandler =
      (LifeSpanHandler*)clientHandler->GetLifeSpanHandler().get();
  if (!lifeSpanHandler.get()) {
    NotifyBrowserCreationFailed(env, objs->jbrowser);
    return;
  }

  CefRefPtr<CefBrowser> parentBrowser =
      GetCefFromJNIObject<CefBrowser>(env, objs->jparentBrowser, "CefBrowser");

  // A non-null Java parent identifies a DevTools request. If its native browser
  // disappeared before this UI-thread task ran, do not fall through and create
  // an unrelated top-level browser with the DevTools object's empty URL.
  if (objs->jparentBrowser != nullptr && !parentBrowser) {
    NotifyBrowserCreationFailed(env, objs->jbrowser);
    return;
  }

  // ShowDevTools focuses an existing DevTools browser without delivering a new
  // OnAfterCreated callback. Do this before queueing a Java wrapper, otherwise
  // that wrapper would permanently poison the FIFO used for future creations.
  if (parentBrowser && parentBrowser->GetHost()->HasDevTools()) {
    CefWindowInfo windowInfo;
    CefBrowserSettings settings;
    CefPoint inspectAt;
    if (objs->jinspectAt != nullptr) {
      int x, y;
      GetJNIPoint(env, objs->jinspectAt, &x, &y);
      inspectAt.Set(x, y);
    }
    parentBrowser->GetHost()->ShowDevTools(windowInfo, clientHandler.get(),
                                           settings, inspectAt);
    NotifyBrowserCreationFailed(env, objs->jbrowser);
    return;
  }

  // Register before either CreateBrowser or ShowDevTools. Both APIs deliver
  // LifeSpanHandler::OnAfterCreated asynchronously, and that callback must be
  // able to bind the exact Java wrapper that initiated this request.
  jobject globalRef = env->NewGlobalRef(objs->jbrowser);
  if (!globalRef) {
    if (env->ExceptionCheck()) {
      env->ExceptionDescribe();
      env->ExceptionClear();
    }
    NotifyBrowserCreationFailed(env, objs->jbrowser);
    return;
  }
  lifeSpanHandler->registerJBrowser(globalRef);

  CefWindowInfo windowInfo;
  CefBrowserSettings settings;

  // If parentBrowser is set, we want to show the DEV-Tools for that browser.
  // Since that cannot be an Alloy-style window, it cannot be integrated into
  // Java UI but must be opened as a pop-up.
  if (parentBrowser.get() != nullptr) {
    CefPoint inspectAt;
    if (objs->jinspectAt != nullptr) {
      int x, y;
      GetJNIPoint(env, objs->jinspectAt, &x, &y);
      inspectAt.Set(x, y);
    }

    parentBrowser->GetHost()->ShowDevTools(windowInfo, clientHandler.get(),
                                           settings, inspectAt);
    return;
  }

  std::string settings_error;
  if (!browser_settings::Convert(env, objs->jbrowserSettings, osr != JNI_FALSE, transparent != JNI_FALSE, &settings, &settings_error)) {
    LogAndClearBrowserSettingsFailure(env, settings_error);
    lifeSpanHandler->unregisterJBrowser(globalRef);
    env->DeleteGlobalRef(globalRef);
    NotifyBrowserCreationFailed(env, objs->jbrowser);
    return;
  }

  if (osr == JNI_FALSE) {
    CefRect rect = {};
    CefRefPtr<WindowHandler> windowHandler =
        (WindowHandler*)clientHandler->GetWindowHandler().get();
    if (windowHandler.get()) {
      windowHandler->GetRect(objs->jbrowser, rect);
    }
#if defined(OS_WIN)
    CefWindowHandle parent = TempWindow::GetWindowHandle();
    if (objs->canvas != nullptr) {
      parent = GetHwndOfCanvas(objs->canvas, env);
    } else {
      // Do not activate hidden browser windows on creation.
      windowInfo.ex_style |= WS_EX_NOACTIVATE;
    }
    windowInfo.SetAsChild(parent, rect);
#elif defined(OS_MACOSX)
    NSWindow* parent = nullptr;
    if (windowHandle != 0) {
      parent = (NSWindow*)windowHandle;
    } else {
      parent = TempWindow::GetWindow();
    }
    CefWindowHandle browserContentView =
        util_mac::CreateBrowserContentView(parent, rect);
    windowInfo.SetAsChild(browserContentView, rect);
#elif defined(OS_LINUX)
    CefWindowHandle parent = TempWindow::GetWindowHandle();
    if (objs->canvas != nullptr) {
      parent = GetDrawableOfCanvas(objs->canvas, env);
    }
    windowInfo.SetAsChild(parent, rect);
#endif
  } else {
    windowInfo.SetAsWindowless((CefWindowHandle)windowHandle);
  }

  CefRefPtr<CefBrowser> browserObj;
  CefString strUrl = GetJNIString(env, static_cast<jstring>(objs->url.get()));

  CefRefPtr<CefRequestContext> context = GetCefFromJNIObject<CefRequestContext>(
      env, objs->jcontext, "CefRequestContext");

  CefRefPtr<CefDictionaryValue> extra_info;
  auto router_configs = BrowserProcessHandler::GetMessageRouterConfigs();
  if (router_configs) {
    // Send the message router config to CefHelperApp::OnBrowserCreated.
    extra_info = CefDictionaryValue::Create();
    extra_info->SetList("router_configs", router_configs);
  }

  // JCEF requires Alloy runtime style for "normal" browsers in order for them
  // to be integratable into Java UI.
  windowInfo.runtime_style = CEF_RUNTIME_STYLE_ALLOY;

  bool result = CefBrowserHost::CreateBrowser(
      windowInfo, clientHandler.get(), strUrl, settings, extra_info, context);
  if (!result) {
    lifeSpanHandler->unregisterJBrowser(globalRef);
    env->DeleteGlobalRef(globalRef);
    NotifyBrowserCreationFailed(env, objs->jbrowser);
    return;
  }
}

class ZoomLevelResult {
 public:
  void Complete(double value) {
    std::lock_guard<std::mutex> lock(mutex_);
    value_ = value;
    completed_ = true;
    condition_.notify_one();
  }

  bool Wait(double* value) {
    std::unique_lock<std::mutex> lock(mutex_);
    if (!condition_.wait_for(lock, std::chrono::seconds(1),
                             [this]() { return completed_; })) {
      return false;
    }
    *value = value_;
    return true;
  }

 private:
  std::mutex mutex_;
  std::condition_variable condition_;
  bool completed_ = false;
  double value_ = 0.0;
};

void getZoomLevel(CefRefPtr<CefBrowser> browser, std::shared_ptr<ZoomLevelResult> result) {
  REQUIRE_UI_THREAD();
  if (!browser.get() || !browser->IsValid()) {
    result->Complete(0.0);
    return;
  }

  CefRefPtr<CefBrowserHost> host = browser->GetHost();
  result->Complete(host.get() ? host->GetZoomLevel() : 0.0);
}

// Use the constants generated from CefBrowser_N.java so query failure remains
// distinguishable from a valid false result without duplicating JNI values.
enum BooleanQueryResult {
  kBooleanQueryFailed = org_cef_browser_CefBrowser_N_BOOLEAN_QUERY_FAILED,
  kBooleanQueryFalse = org_cef_browser_CefBrowser_N_BOOLEAN_QUERY_FALSE,
  kBooleanQueryTrue = org_cef_browser_CefBrowser_N_BOOLEAN_QUERY_TRUE,
};

enum class ZoomLevelQuery {
  kDefault,
  kCurrent,
};

enum class BrowserBooleanQuery {
  kAudioMuted,
  kFullscreen,
};

// LifeSpanHandler clears the raw N_CefHandle immediately after Java onBeforeClose returns. Older
// CefBrowser_N bytecode can call retained JNI entries without the Java admission gate, so every
// affected native entry must take the same Java lifecycle monitor while checking state and
// converting that raw pointer into an owning CefRefPtr. Never retain this monitor across a CEF UI
// task post or wait because onBeforeClose also needs it on the UI thread.
class ScopedBrowserLifecycleMonitor {
 public:
  ScopedBrowserLifecycleMonitor(JNIEnv* env, jobject browser) : env_(env), browser_(browser), entered_(env && browser && env->MonitorEnter(browser) == JNI_OK) {}

  ScopedBrowserLifecycleMonitor(const ScopedBrowserLifecycleMonitor&) = delete;
  ScopedBrowserLifecycleMonitor& operator=(const ScopedBrowserLifecycleMonitor&) = delete;

  ~ScopedBrowserLifecycleMonitor() {
    if (entered_)
      env_->MonitorExit(browser_);
  }

  bool entered() const { return entered_; }

 private:
  JNIEnv* const env_;
  jobject const browser_;
  const bool entered_;
};

CefRefPtr<CefBrowser> GetLifecycleSafeJNIBrowser(JNIEnv* env, jobject jbrowser, const bool allow_closing = false) {
  CefRefPtr<CefBrowser> browser;
  {
    ScopedBrowserLifecycleMonitor monitor(env, jbrowser);
    if (!monitor.entered())
      return nullptr;

    ScopedJNIClass cls(env, env->GetObjectClass(jbrowser));
    int closing = 0;
    int closed = 0;
    if (!cls || !GetJNIFieldBoolean(env, cls, jbrowser, "isClosing_", &closing) || !GetJNIFieldBoolean(env, cls, jbrowser, "isClosed_", &closed) || (!allow_closing && closing) || closed)
      return nullptr;

    browser = GetJNIBrowser(env, jbrowser);
  }
  return env->ExceptionCheck() ? nullptr : browser;
}

// CEF zoom queries are UI-thread-only. Every posted task retains the browser
// instead of only the host so it can recheck validity after OnBeforeClose.
void queryCanZoom(CefRefPtr<CefBrowser> browser, cef_zoom_command_t command, CefRefPtr<IntCallback> callback) {
  REQUIRE_UI_THREAD();
  if (!browser.get() || !browser->IsValid()) {
    callback->onComplete(kBooleanQueryFailed);
    return;
  }

  CefRefPtr<CefBrowserHost> host = browser->GetHost();
  if (!host.get()) {
    callback->onComplete(kBooleanQueryFailed);
    return;
  }

  callback->onComplete(host->CanZoom(command) ? kBooleanQueryTrue : kBooleanQueryFalse);
}

void queryZoomLevel(CefRefPtr<CefBrowser> browser, ZoomLevelQuery query, CefRefPtr<DoubleCallback> callback) {
  REQUIRE_UI_THREAD();
  if (!browser.get() || !browser->IsValid()) {
    callback->onComplete(false, 0.0);
    return;
  }

  CefRefPtr<CefBrowserHost> host = browser->GetHost();
  if (!host.get()) {
    callback->onComplete(false, 0.0);
    return;
  }

  const double value = query == ZoomLevelQuery::kDefault ? host->GetDefaultZoomLevel() : host->GetZoomLevel();
  callback->onComplete(true, value);
}

void startZoomLevelQuery(JNIEnv* env, jobject obj, jobject jdoubleCallback, ZoomLevelQuery query) {
  CefRefPtr<DoubleCallback> callback = new DoubleCallback(env, jdoubleCallback);
  CefRefPtr<CefBrowser> browser = GetLifecycleSafeJNIBrowser(env, obj);
  if (!browser.get() || !browser->IsValid()) {
    if (!env->ExceptionCheck())
      callback->onComplete(false, 0.0);
    return;
  }

  if (CefCurrentlyOn(TID_UI)) {
    queryZoomLevel(browser, query, callback);
    return;
  }

  if (!CefPostTask(TID_UI, base::BindOnce(queryZoomLevel, browser, query, callback)))
    callback->onComplete(false, 0.0);
}

// These CefBrowserHost boolean queries are UI-thread-only. Keep the browser and Java callback
// alive across the posted task, then recheck validity because OnBeforeClose may run first.
void queryBrowserBooleanState(CefRefPtr<CefBrowser> browser, BrowserBooleanQuery query, CefRefPtr<IntCallback> callback) {
  REQUIRE_UI_THREAD();
  if (!browser.get() || !browser->IsValid()) {
    callback->onComplete(kBooleanQueryFailed);
    return;
  }

  CefRefPtr<CefBrowserHost> host = browser->GetHost();
  if (!host.get()) {
    callback->onComplete(kBooleanQueryFailed);
    return;
  }

  bool value = false;
  switch (query) {
    case BrowserBooleanQuery::kAudioMuted:
      value = host->IsAudioMuted();
      break;
    case BrowserBooleanQuery::kFullscreen:
      value = host->IsFullscreen();
      break;
    default:
      callback->onComplete(kBooleanQueryFailed);
      return;
  }
  callback->onComplete(value ? kBooleanQueryTrue : kBooleanQueryFalse);
}

void startBrowserBooleanQuery(JNIEnv* env, jobject jbrowser, jobject jintCallback, BrowserBooleanQuery query) {
  CefRefPtr<IntCallback> callback = new IntCallback(env, jintCallback);
  CefRefPtr<CefBrowser> browser = GetLifecycleSafeJNIBrowser(env, jbrowser);
  if (!browser.get() || !browser->IsValid()) {
    if (!env->ExceptionCheck())
      callback->onComplete(kBooleanQueryFailed);
    return;
  }

  if (CefCurrentlyOn(TID_UI)) {
    queryBrowserBooleanState(browser, query, callback);
    return;
  }

  if (!CefPostTask(TID_UI, base::BindOnce(queryBrowserBooleanState, browser, query, callback)))
    callback->onComplete(kBooleanQueryFailed);
}

void executeDevToolsMethod(CefRefPtr<CefBrowserHost> host,
                           const CefString& method,
                           const CefString& parametersAsJson,
                           CefRefPtr<IntCallback> callback) {
  CefRefPtr<CefDictionaryValue> parameters = nullptr;
  if (!parametersAsJson.empty()) {
    CefRefPtr<CefValue> value = CefParseJSON(
        parametersAsJson, cef_json_parser_options_t::JSON_PARSER_RFC);

    if (!value || value->GetType() != VTYPE_DICTIONARY) {
      callback->onComplete(0);
      return;
    }

    parameters = value->GetDictionary();
  }

  callback->onComplete(host->ExecuteDevToolsMethod(0, method, parameters));
}

void OnAfterParentChanged(CefRefPtr<CefBrowser> browser) {
  if (!CefCurrentlyOn(TID_UI)) {
    CefPostTask(TID_UI, base::BindOnce(&OnAfterParentChanged, browser));
    return;
  }

  if (browser->GetHost()->GetClient()) {
    CefRefPtr<LifeSpanHandler> lifeSpanHandler =
        (LifeSpanHandler*)browser->GetHost()
            ->GetClient()
            ->GetLifeSpanHandler()
            .get();
    if (lifeSpanHandler) {
      lifeSpanHandler->OnAfterParentChanged(browser);
    }
  }
}

#if defined(OS_LINUX)
class LinuxUiTaskCompletion {
 public:
  void Complete() {
    std::lock_guard<std::mutex> lock(mutex_);
    completed_ = true;
    condition_.notify_one();
  }

  bool Wait(std::chrono::milliseconds timeout) {
    std::unique_lock<std::mutex> lock(mutex_);
    return condition_.wait_for(lock, timeout, [this]() { return completed_; });
  }

 private:
  std::mutex mutex_;
  std::condition_variable condition_;
  bool completed_ = false;
};

void OnAfterParentChangedAndSignal(CefRefPtr<CefBrowser> browser, std::shared_ptr<LinuxUiTaskCompletion> completion) {
  OnAfterParentChanged(browser);
  completion->Complete();
}

void SignalLinuxWindowedClose(std::shared_ptr<LinuxUiTaskCompletion> completion) {
  completion->Complete();
}

void DestroyLinuxBrowserAndSignal(CefRefPtr<CefBrowser> browser, std::shared_ptr<LinuxUiTaskCompletion> completion) {
  util::DestroyCefBrowser(browser);
  // Run the signal on the next CEF UI turn. This guarantees that the
  // forced-close DoClose callback has unwound before the waiting AWT handler is
  // allowed to destroy the native parent hierarchy.
  if (!CefPostTask(TID_UI, base::BindOnce(&SignalLinuxWindowedClose, completion)))
    completion->Complete();
}
#endif

CefPdfPrintSettings GetJNIPdfPrintSettings(JNIEnv* env, jobject obj) {
  CefString tmp;
  CefPdfPrintSettings settings;
  if (!obj)
    return settings;

  ScopedJNIClass cls(env, "org/cef/misc/CefPdfPrintSettings");
  if (!cls)
    return settings;

  GetJNIFieldBoolean(env, cls, obj, "landscape", &settings.landscape);

  GetJNIFieldBoolean(env, cls, obj, "print_background",
                     &settings.print_background);

  GetJNIFieldDouble(env, cls, obj, "scale", &settings.scale);

  GetJNIFieldDouble(env, cls, obj, "paper_width", &settings.paper_width);
  GetJNIFieldDouble(env, cls, obj, "paper_height", &settings.paper_height);

  GetJNIFieldBoolean(env, cls, obj, "prefer_css_page_size",
                     &settings.prefer_css_page_size);

  jobject obj_margin_type = nullptr;
  if (GetJNIFieldObject(env, cls, obj, "margin_type", &obj_margin_type,
                        "Lorg/cef/misc/CefPdfPrintSettings$MarginType;")) {
    ScopedJNIObjectLocal margin_type(env, obj_margin_type);
    if (IsJNIEnumValue(env, margin_type,
                       "org/cef/misc/CefPdfPrintSettings$MarginType",
                       "DEFAULT")) {
      settings.margin_type = PDF_PRINT_MARGIN_DEFAULT;
    } else if (IsJNIEnumValue(env, margin_type,
                              "org/cef/misc/CefPdfPrintSettings$MarginType",
                              "NONE")) {
      settings.margin_type = PDF_PRINT_MARGIN_NONE;
    } else if (IsJNIEnumValue(env, margin_type,
                              "org/cef/misc/CefPdfPrintSettings$MarginType",
                              "CUSTOM")) {
      settings.margin_type = PDF_PRINT_MARGIN_CUSTOM;
    }
  }

  GetJNIFieldDouble(env, cls, obj, "margin_top", &settings.margin_top);
  GetJNIFieldDouble(env, cls, obj, "margin_bottom", &settings.margin_bottom);
  GetJNIFieldDouble(env, cls, obj, "margin_right", &settings.margin_right);
  GetJNIFieldDouble(env, cls, obj, "margin_left", &settings.margin_left);

  if (GetJNIFieldString(env, cls, obj, "page_ranges", &tmp) && !tmp.empty()) {
    CefString(&settings.page_ranges) = tmp;
    tmp.clear();
  }

  GetJNIFieldBoolean(env, cls, obj, "display_header_footer",
                     &settings.display_header_footer);

  if (GetJNIFieldString(env, cls, obj, "header_template", &tmp) &&
      !tmp.empty()) {
    CefString(&settings.header_template) = tmp;
    tmp.clear();
  }

  if (GetJNIFieldString(env, cls, obj, "footer_template", &tmp) &&
      !tmp.empty()) {
    CefString(&settings.footer_template) = tmp;
    tmp.clear();
  }

  GetJNIFieldBoolean(env, cls, obj, "generate_tagged_pdf",
                     &settings.generate_tagged_pdf);

  GetJNIFieldBoolean(env, cls, obj, "generate_document_outline",
                     &settings.generate_document_outline);

  return settings;
}

// JNI CefRegistration object.
class ScopedJNIRegistration : public ScopedJNIObject<CefRegistration> {
 public:
  ScopedJNIRegistration(JNIEnv* env, CefRefPtr<CefRegistration> obj)
      : ScopedJNIObject<CefRegistration>(env,
                                         obj,
                                         "org/cef/browser/CefRegistration_N",
                                         "CefRegistration") {}
};

}  // namespace

JNIEXPORT jobject JNICALL Java_org_cef_browser_CefBrowser_1N_N_1ConvertBrowserSettingsForTesting(JNIEnv* env, jclass, jobject jsettings, jboolean osr, jboolean transparent) {
  CefBrowserSettings settings;
  std::string error;
  if (!browser_settings::Convert(env, jsettings, osr != JNI_FALSE, transparent != JNI_FALSE, &settings, &error)) {
    if (!env->ExceptionCheck()) {
      ScopedJNIClass exception_class(env, "java/lang/IllegalArgumentException");
      if (exception_class)
        env->ThrowNew(exception_class, error.c_str());
    }
    return nullptr;
  }

  jobject snapshot = browser_settings::NewSnapshot(env, settings);
  if (!snapshot && !env->ExceptionCheck()) {
    ScopedJNIClass exception_class(env, "java/lang/IllegalStateException");
    if (exception_class)
      env->ThrowNew(exception_class, "Failed to create CefBrowserSettings test snapshot");
  }
  return snapshot;
}

JNIEXPORT jboolean JNICALL Java_org_cef_browser_CefBrowser_1N_N_1IsOnCefUiThreadForTesting(JNIEnv*, jclass) {
  return CefCurrentlyOn(TID_UI) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_org_cef_browser_CefBrowser_1N_N_1ResolveLinuxNativeKeyCodeForTesting(JNIEnv*, jclass, jlong supplied_native_key_code, jint key_code, jint key_location, jboolean typed, jboolean awt) {
  return key_event_platform_util::ResolveLinuxNativeKeyCode(supplied_native_key_code, key_code, key_location, typed == JNI_TRUE, awt == JNI_TRUE ? InputEventSemantics::kAwt : InputEventSemantics::kGlfw);
}

JNIEXPORT jint JNICALL Java_org_cef_browser_CefBrowser_1N_N_1ResolveWindowsNativeKeyCodeForTesting(JNIEnv*, jclass, jlong supplied_scan_code, jint mapped_scan_code, jboolean extended) {
  return key_event_platform_util::ResolveWindowsNativeKeyCode(supplied_scan_code, static_cast<std::uint32_t>(mapped_scan_code), extended == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1CreateBrowser(JNIEnv* env,
                                                    jobject jbrowser,
                                                    jobject jclientHandler,
                                                    jlong windowHandle,
                                                    jstring url,
                                                    jboolean osr,
                                                    jboolean transparent,
                                                    jobject canvas,
                                                    jobject jcontext,
                                                    jobject browserSettings) {
  std::shared_ptr<JNIObjectsForCreate> objs(
      new JNIObjectsForCreate(env, jbrowser, nullptr, jclientHandler, url,
                              canvas, jcontext, nullptr, browserSettings));
  if (CefCurrentlyOn(TID_UI)) {
    create(objs, windowHandle, osr, transparent);
  } else {
    if (!CefPostTask(TID_UI, base::BindOnce(&create, objs, windowHandle, osr,
                                            transparent))) {
      return JNI_FALSE;
    }
  }
  return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1CreateDevTools(JNIEnv* env,
                                                     jobject jbrowser,
                                                     jobject jparent,
                                                     jobject jclientHandler,
                                                     jlong windowHandle,
                                                     jboolean osr,
                                                     jboolean transparent,
                                                     jobject canvas,
                                                     jobject inspect) {
  std::shared_ptr<JNIObjectsForCreate> objs(
      new JNIObjectsForCreate(env, jbrowser, jparent, jclientHandler, nullptr,
                              canvas, nullptr, inspect, nullptr));
  if (CefCurrentlyOn(TID_UI)) {
    create(objs, windowHandle, osr, transparent);
  } else {
    if (!CefPostTask(TID_UI, base::BindOnce(&create, objs, windowHandle, osr,
                                            transparent))) {
      return JNI_FALSE;
    }
  }
  return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1ExecuteDevToolsMethod(
    JNIEnv* env,
    jobject jbrowser,
    jstring method,
    jstring parametersAsJson,
    jobject jcallback) {
  CefRefPtr<IntCallback> callback = new IntCallback(env, jcallback);

  CefRefPtr<CefBrowser> browser = GetJNIBrowser(env, jbrowser);
  if (!browser.get()) {
    callback->onComplete(0);
    return;
  }

  CefString strMethod = GetJNIString(env, method);
  CefString strParametersAsJson = GetJNIString(env, parametersAsJson);

  if (CefCurrentlyOn(TID_UI)) {
    executeDevToolsMethod(browser->GetHost(), strMethod, strParametersAsJson,
                          callback);
  } else {
    if (!CefPostTask(
            TID_UI, base::BindOnce(executeDevToolsMethod, browser->GetHost(),
                                   strMethod, strParametersAsJson, callback))) {
      callback->onComplete(0);
    }
  }
}

JNIEXPORT jobject JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1AddDevToolsMessageObserver(
    JNIEnv* env,
    jobject jbrowser,
    jobject jobserver) {
  CefRefPtr<CefBrowser> browser =
      JNI_GET_BROWSER_OR_RETURN(env, jbrowser, NULL);

  CefRefPtr<DevToolsMessageObserver> observer =
      new DevToolsMessageObserver(env, jobserver);

  CefRefPtr<CefRegistration> registration =
      browser->GetHost()->AddDevToolsMessageObserver(observer);

  ScopedJNIRegistration jregistration(env, registration);
  return jregistration.Release();
}

JNIEXPORT jlong JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1GetWindowHandle(JNIEnv* env,
                                                      jobject obj,
                                                      jlong displayHandle) {
  CefWindowHandle windowHandle = kNullWindowHandle;
#if defined(OS_WIN)
  windowHandle = ::WindowFromDC((HDC)displayHandle);
#elif defined(OS_LINUX)
  return displayHandle;
#elif defined(OS_MACOSX)
  ASSERT(util_mac::IsNSView((void*)displayHandle));
#endif
  return (jlong)windowHandle;
}

JNIEXPORT jboolean JNICALL Java_org_cef_browser_CefBrowser_1N_N_1IsValid(JNIEnv* env, jobject obj) {
  CefRefPtr<CefBrowser> browser = GetLifecycleSafeJNIBrowser(env, obj, true);
  return browser.get() && browser->IsValid() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1CanGoBack(JNIEnv* env, jobject obj) {
  CefRefPtr<CefBrowser> browser =
      JNI_GET_BROWSER_OR_RETURN(env, obj, JNI_FALSE);
  return browser->CanGoBack() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1GoBack(JNIEnv* env, jobject obj) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  browser->GoBack();
}

JNIEXPORT jboolean JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1CanGoForward(JNIEnv* env, jobject obj) {
  CefRefPtr<CefBrowser> browser =
      JNI_GET_BROWSER_OR_RETURN(env, obj, JNI_FALSE);
  return browser->CanGoForward() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1GoForward(JNIEnv* env, jobject obj) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  browser->GoForward();
}

JNIEXPORT jboolean JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1IsLoading(JNIEnv* env, jobject obj) {
  CefRefPtr<CefBrowser> browser =
      JNI_GET_BROWSER_OR_RETURN(env, obj, JNI_FALSE);
  return browser->IsLoading() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1Reload(JNIEnv* env, jobject obj) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  browser->Reload();
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1ReloadIgnoreCache(JNIEnv* env,
                                                        jobject obj) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  browser->ReloadIgnoreCache();
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1StopLoad(JNIEnv* env, jobject obj) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  browser->StopLoad();
}

JNIEXPORT jint JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1GetIdentifier(JNIEnv* env, jobject obj) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj, -1);
  return browser->GetIdentifier();
}

JNIEXPORT jboolean JNICALL Java_org_cef_browser_CefBrowser_1N_N_1IsSame(JNIEnv* env, jobject obj, jobject jthat) {
  ScopedJNIClass native_browser_class(env, "org/cef/browser/CefBrowser_N");
  if (!native_browser_class || !jthat || env->IsInstanceOf(jthat, native_browser_class) == JNI_FALSE)
    return JNI_FALSE;

  // Promote each raw handle under its own Java lifecycle monitor. Keeping both owning references
  // after those independent scopes avoids deadlock between two simultaneous reversed comparisons
  // without allowing OnBeforeClose to release an operand during the native call.
  CefRefPtr<CefBrowser> browser = GetLifecycleSafeJNIBrowser(env, obj, true);
  if (!browser.get())
    return JNI_FALSE;
  CefRefPtr<CefBrowser> that = GetLifecycleSafeJNIBrowser(env, jthat, true);
  if (!that.get() || !browser->IsValid() || !that->IsValid())
    return JNI_FALSE;
  return browser->IsSame(that) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1GetMainFrame(JNIEnv* env, jobject obj) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj, nullptr);
  CefRefPtr<CefFrame> frame = browser->GetMainFrame();
  if (!frame)
    return nullptr;
  ScopedJNIFrame jframe(env, frame);
  return jframe.Release();
}

JNIEXPORT jobject JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1GetFocusedFrame(JNIEnv* env,
                                                      jobject obj) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj, nullptr);
  CefRefPtr<CefFrame> frame = browser->GetFocusedFrame();
  if (!frame)
    return nullptr;
  ScopedJNIFrame jframe(env, frame);
  return jframe.Release();
}

JNIEXPORT jobject JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1GetFrameByIdentifier(JNIEnv* env,
                                                           jobject obj,
                                                           jstring identifier) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj, nullptr);
  CefRefPtr<CefFrame> frame =
      browser->GetFrameByIdentifier(GetJNIString(env, identifier));
  if (!frame)
    return nullptr;
  ScopedJNIFrame jframe(env, frame);
  return jframe.Release();
}

JNIEXPORT jobject JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1GetFrameByName(JNIEnv* env,
                                                     jobject obj,
                                                     jstring name) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj, nullptr);
  CefRefPtr<CefFrame> frame = browser->GetFrameByName(GetJNIString(env, name));
  if (!frame)
    return nullptr;
  ScopedJNIFrame jframe(env, frame);
  return jframe.Release();
}

JNIEXPORT jint JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1GetFrameCount(JNIEnv* env, jobject obj) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj, -1);
  return (jint)browser->GetFrameCount();
}

JNIEXPORT jobject JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1GetFrameIdentifiers(JNIEnv* env,
                                                          jobject obj) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj, nullptr);
  std::vector<CefString> identifiers;
  browser->GetFrameIdentifiers(identifiers);
  return NewJNIStringVector(env, identifiers);
}

JNIEXPORT jobject JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1GetFrameNames(JNIEnv* env, jobject obj) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj, nullptr);
  std::vector<CefString> names;
  browser->GetFrameNames(names);
  return NewJNIStringVector(env, names);
}

JNIEXPORT jboolean JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1IsPopup(JNIEnv* env, jobject obj) {
  CefRefPtr<CefBrowser> browser =
      JNI_GET_BROWSER_OR_RETURN(env, obj, JNI_FALSE);
  return browser->IsPopup() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1HasDocument(JNIEnv* env, jobject obj) {
  CefRefPtr<CefBrowser> browser =
      JNI_GET_BROWSER_OR_RETURN(env, obj, JNI_FALSE);
  return browser->HasDocument() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1ViewSource(JNIEnv* env, jobject obj) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  CefRefPtr<CefFrame> mainFrame = browser->GetMainFrame();
  CefPostTask(TID_UI, base::BindOnce(&CefFrame::ViewSource, mainFrame.get()));
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1GetSource(JNIEnv* env,
                                                jobject obj,
                                                jobject jvisitor) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  browser->GetMainFrame()->GetSource(new StringVisitor(env, jvisitor));
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1GetText(JNIEnv* env,
                                              jobject obj,
                                              jobject jvisitor) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  browser->GetMainFrame()->GetText(new StringVisitor(env, jvisitor));
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1LoadRequest(JNIEnv* env,
                                                  jobject obj,
                                                  jobject jrequest) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  ScopedJNIRequest requestObj(env);
  requestObj.SetHandle(jrequest, false /* should_delete */);
  CefRefPtr<CefRequest> request = requestObj.GetCefObject();
  if (!request)
    return;
  browser->GetMainFrame()->LoadRequest(request);
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1LoadURL(JNIEnv* env,
                                              jobject obj,
                                              jstring url) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  browser->GetMainFrame()->LoadURL(GetJNIString(env, url));
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1ExecuteJavaScript(JNIEnv* env,
                                                        jobject obj,
                                                        jstring code,
                                                        jstring url,
                                                        jint line) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  browser->GetMainFrame()->ExecuteJavaScript(GetJNIString(env, code),
                                             GetJNIString(env, url), line);
}

JNIEXPORT jstring JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1GetURL(JNIEnv* env, jobject obj) {
  jstring tmp = NewJNIString(env, "");
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj, tmp);
  return NewJNIString(env, browser->GetMainFrame()->GetURL());
}

JNIEXPORT jboolean JNICALL Java_org_cef_browser_CefBrowser_1N_N_1IsWindowRenderingDisabled(JNIEnv* env, jobject obj) {
  CefRefPtr<CefBrowser> browser = GetLifecycleSafeJNIBrowser(env, obj, true);
  if (!browser.get() || !browser->IsValid())
    return JNI_FALSE;
  CefRefPtr<CefBrowserHost> host = browser->GetHost();
  return host.get() && host->IsWindowRenderingDisabled() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1Close(JNIEnv* env,
                                            jobject obj,
                                            jboolean force) {
  // Acquire an owning reference while holding the Java lifecycle monitor, then release the
  // monitor before posting, waiting or invoking CEF because close callbacks reenter Java.
  CefRefPtr<CefBrowser> browser = GetLifecycleSafeJNIBrowser(env, obj, true);
  if (!browser.get() || !browser->IsValid())
    return;
  CefRefPtr<CefBrowserHost> host = browser->GetHost();
  if (!host.get())
    return;
  if (force != JNI_FALSE) {
    if (host->IsWindowRenderingDisabled()) {
      host->CloseBrowser(true);
    } else {
      // Destroy the native window representation.
#if defined(OS_LINUX)
      // Linux destruction delegates to CloseBrowser instead of destroying an OS
      // window directly. Wait until the resulting DoClose callback has unwound
      // before returning to the AWT close handler, which will immediately tear
      // down the X11 parent hierarchy.
      if (CefCurrentlyOn(TID_UI)) {
        util::DestroyCefBrowser(browser);
      } else {
        std::shared_ptr<LinuxUiTaskCompletion> completion = std::make_shared<LinuxUiTaskCompletion>();
        if (!CefPostTask(TID_UI, base::BindOnce(&DestroyLinuxBrowserAndSignal, browser, completion)) || !completion->Wait(std::chrono::seconds(5)))
          LOG(WARNING) << "Failed or timed out closing Linux browser before "
                          "AWT parent disposal";
      }
#else
      if (CefCurrentlyOn(TID_UI))
        util::DestroyCefBrowser(browser);
      else
        CefPostTask(TID_UI, base::BindOnce(&util::DestroyCefBrowser, browser));
#endif
    }
  } else {
    host->CloseBrowser(false);
  }
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1SetFocus(JNIEnv* env,
                                               jobject obj,
                                               jboolean enable) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  browser->GetHost()->SetFocus(enable != JNI_FALSE);
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefBrowser_1N_N_1SetWindowVisibility(JNIEnv* env, jobject obj, jboolean visible) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  CefRefPtr<CefBrowserHost> host = browser->GetHost();

  if (host->IsWindowRenderingDisabled()) {
    host->WasHidden(visible == JNI_FALSE);
    return;
  }

#if defined(OS_MACOSX)
  util_mac::SetVisibility(host->GetWindowHandle(), visible != JNI_FALSE);
#endif
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefBrowser_1N_N_1NotifyScreenInfoChanged(JNIEnv* env, jobject obj) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  browser->GetHost()->NotifyScreenInfoChanged();
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefBrowser_1N_N_1CanZoom(JNIEnv* env, jobject obj, jint commandValue, jobject jintCallback) {
  CefRefPtr<IntCallback> callback = new IntCallback(env, jintCallback);
  cef_zoom_command_t command = CEF_ZOOM_COMMAND_RESET;
  if (!GetZoomCommand(env, commandValue, &command))
    return;

  CefRefPtr<CefBrowser> browser = GetLifecycleSafeJNIBrowser(env, obj);
  if (!browser.get() || !browser->IsValid()) {
    if (!env->ExceptionCheck())
      callback->onComplete(kBooleanQueryFailed);
    return;
  }

  if (CefCurrentlyOn(TID_UI)) {
    queryCanZoom(browser, command, callback);
    return;
  }

  if (!CefPostTask(TID_UI, base::BindOnce(queryCanZoom, browser, command, callback)))
    callback->onComplete(kBooleanQueryFailed);
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefBrowser_1N_N_1Zoom(JNIEnv* env, jobject obj, jint commandValue) {
  cef_zoom_command_t command = CEF_ZOOM_COMMAND_RESET;
  if (!GetZoomCommand(env, commandValue, &command))
    return;

  CefRefPtr<CefBrowser> browser = GetLifecycleSafeJNIBrowser(env, obj);
  if (!browser.get() || !browser->IsValid())
    return;
  CefRefPtr<CefBrowserHost> host = browser->GetHost();
  if (host.get())
    host->Zoom(command);
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefBrowser_1N_N_1GetDefaultZoomLevel(JNIEnv* env, jobject obj, jobject jdoubleCallback) {
  startZoomLevelQuery(env, obj, jdoubleCallback, ZoomLevelQuery::kDefault);
}

JNIEXPORT jdouble JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1GetZoomLevel(JNIEnv* env, jobject obj) {
  CefRefPtr<CefBrowser> browser = GetLifecycleSafeJNIBrowser(env, obj);
  if (!browser.get() || !browser->IsValid())
    return 0.0;
  CefRefPtr<CefBrowserHost> host = browser->GetHost();
  if (!host.get())
    return 0.0;
  double result = 0.0;
  if (CefCurrentlyOn(TID_UI))
    result = host->GetZoomLevel();
  else {
    std::shared_ptr<ZoomLevelResult> asyncResult =
        std::make_shared<ZoomLevelResult>();
    if (!CefPostTask(TID_UI, base::BindOnce(getZoomLevel, browser, asyncResult)) ||
        !asyncResult->Wait(&result)) {
      LOG(WARNING) << "Failed or timed out retrieving browser zoom level";
    }
  }
  return result;
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefBrowser_1N_N_1GetZoomLevelAsync(JNIEnv* env, jobject obj, jobject jdoubleCallback) {
  startZoomLevelQuery(env, obj, jdoubleCallback, ZoomLevelQuery::kCurrent);
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1SetZoomLevel(JNIEnv* env,
                                                   jobject obj,
                                                   jdouble zoom) {
  CefRefPtr<CefBrowser> browser = GetLifecycleSafeJNIBrowser(env, obj);
  if (!browser.get() || !browser->IsValid())
    return;
  CefRefPtr<CefBrowserHost> host = browser->GetHost();
  if (host.get())
    host->SetZoomLevel(zoom);
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1RunFileDialog(JNIEnv* env,
                                                    jobject obj,
                                                    jobject jmode,
                                                    jstring jtitle,
                                                    jstring jdefaultFilePath,
                                                    jobject jacceptFilters,
                                                    jint selectedAcceptFilter,
                                                    jobject jcallback) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);

  std::vector<CefString> accept_types;
  GetJNIStringVector(env, jacceptFilters, accept_types);

  CefBrowserHost::FileDialogMode mode;
  if (IsJNIEnumValue(env, jmode,
                     "org/cef/handler/CefDialogHandler$FileDialogMode",
                     "FILE_DIALOG_OPEN")) {
    mode = FILE_DIALOG_OPEN;
  } else if (IsJNIEnumValue(env, jmode,
                            "org/cef/handler/CefDialogHandler$FileDialogMode",
                            "FILE_DIALOG_OPEN_MULTIPLE")) {
    mode = FILE_DIALOG_OPEN_MULTIPLE;
  } else if (IsJNIEnumValue(env, jmode,
                            "org/cef/handler/CefDialogHandler$FileDialogMode",
                            "FILE_DIALOG_OPEN_FOLDER")) {
    mode = FILE_DIALOG_OPEN_FOLDER;
  } else if (IsJNIEnumValue(env, jmode,
                            "org/cef/handler/CefDialogHandler$FileDialogMode",
                            "FILE_DIALOG_SAVE")) {
    mode = FILE_DIALOG_SAVE;
  } else {
    mode = FILE_DIALOG_OPEN;
  }

  browser->GetHost()->RunFileDialog(
      mode, GetJNIString(env, jtitle), GetJNIString(env, jdefaultFilePath),
      accept_types, new RunFileDialogCallback(env, jcallback));
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1StartDownload(JNIEnv* env,
                                                    jobject obj,
                                                    jstring url) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  browser->GetHost()->StartDownload(GetJNIString(env, url));
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1Print(JNIEnv* env, jobject obj) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  browser->GetHost()->Print();
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1PrintToPDF(JNIEnv* env,
                                                 jobject obj,
                                                 jstring jpath,
                                                 jobject jsettings,
                                                 jobject jcallback) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);

  CefPdfPrintSettings settings = GetJNIPdfPrintSettings(env, jsettings);

  browser->GetHost()->PrintToPDF(GetJNIString(env, jpath), settings,
                                 new PdfPrintCallback(env, jcallback));
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1Find(JNIEnv* env,
                                           jobject obj,
                                           jstring searchText,
                                           jboolean forward,
                                           jboolean matchCase,
                                           jboolean findNext) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  browser->GetHost()->Find(GetJNIString(env, searchText),
                           (forward != JNI_FALSE), (matchCase != JNI_FALSE),
                           (findNext != JNI_FALSE));
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1StopFinding(JNIEnv* env,
                                                  jobject obj,
                                                  jboolean clearSelection) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  browser->GetHost()->StopFinding(clearSelection != JNI_FALSE);
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1ShowDevTools(JNIEnv* env,
                                                   jobject obj,
                                                   jobject jinspectAt) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  CefPoint inspectAt;
  if (jinspectAt) {
    int x, y;
    GetJNIPoint(env, jinspectAt, &x, &y);
    inspectAt.Set(x, y);
  }

  CefWindowInfo windowInfo;
  CefBrowserSettings settings;
  browser->GetHost()->ShowDevTools(windowInfo, browser->GetHost()->GetClient(),
                                   settings, inspectAt);
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1CloseDevTools(JNIEnv* env, jobject obj) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  browser->GetHost()->CloseDevTools();
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1ReplaceMisspelling(JNIEnv* env,
                                                         jobject obj,
                                                         jstring jword) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  browser->GetHost()->ReplaceMisspelling(GetJNIString(env, jword));
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1WasResized(JNIEnv* env,
                                                 jobject obj,
                                                 jint width,
                                                 jint height) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  if (browser->GetHost()->IsWindowRenderingDisabled()) {
    browser->GetHost()->WasResized();
  }
#if (defined(OS_WIN) || defined(OS_LINUX))
  else {
    CefWindowHandle browserHandle = browser->GetHost()->GetWindowHandle();
    if (CefCurrentlyOn(TID_UI)) {
      util::SetWindowSize(browserHandle, width, height);
    } else {
      CefPostTask(TID_UI, base::BindOnce(util::SetWindowSize, browserHandle,
                                         (int)width, (int)height));
    }
  }
#endif
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefBrowser_1N_N_1Invalidate(JNIEnv* env, jobject obj) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  browser->GetHost()->Invalidate(PET_VIEW);
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefBrowser_1N_N_1InvalidatePaintElement(JNIEnv* env, jobject obj, jint value) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  CefBrowserHost::PaintElementType type;
  if (!GetPaintElementType(env, value, &type)) return;
  browser->GetHost()->Invalidate(type);
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefBrowser_1N_N_1SendCaptureLostEvent(JNIEnv* env, jobject obj) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  browser->GetHost()->SendCaptureLostEvent();
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1SendKeyEvent(JNIEnv* env,
                                                   jobject obj,
                                                   jobject key_event) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  SendJavaKeyEvent(env, browser, key_event, InputEventSemantics::kGlfw, false);
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefBrowser_1N_N_1SendKeyEventAwt(JNIEnv* env, jobject obj, jobject key_event, jboolean repeated) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  SendJavaKeyEvent(env, browser, key_event, InputEventSemantics::kAwt, repeated == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1SendMouseEvent(JNIEnv* env,
                                                     jobject obj,
                                                     jobject mouse_event) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  SendJavaMouseEvent(env, browser, mouse_event, InputEventSemantics::kGlfw);
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefBrowser_1N_N_1SendMouseEventAwt(JNIEnv* env, jobject obj, jobject mouse_event) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  SendJavaMouseEvent(env, browser, mouse_event, InputEventSemantics::kAwt);
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1SendMouseWheelEvent(
    JNIEnv* env,
    jobject obj,
    jobject mouse_wheel_event) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  SendJavaMouseWheelEvent(env, browser, mouse_wheel_event, InputEventSemantics::kGlfw);
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefBrowser_1N_N_1SendMouseWheelEventAwt(JNIEnv* env, jobject obj, jobject mouse_wheel_event) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  SendJavaMouseWheelEvent(env, browser, mouse_wheel_event, InputEventSemantics::kAwt);
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1DragTargetDragEnter(JNIEnv* env,
                                                          jobject obj,
                                                          jobject jdragData,
                                                          jobject pos,
                                                          jint jmodifiers,
                                                          jint allowedOps) {
  CefRefPtr<CefDragData> drag_data =
      GetCefFromJNIObject<CefDragData>(env, jdragData, "CefDragData");
  if (!drag_data.get())
    return;

  CefMouseEvent cef_event;
  if (!GetJNIPoint(env, pos, &cef_event.x, &cef_event.y))
    return;
  // The Java drag API explicitly accepts org.cef.misc.EventFlags, which are
  // already CEF values and must never be reinterpreted as AWT or GLFW masks.
  cef_event.modifiers = jmodifiers;

  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  browser->GetHost()->DragTargetDragEnter(
      drag_data, cef_event, (CefBrowserHost::DragOperationsMask)allowedOps);
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1DragTargetDragOver(JNIEnv* env,
                                                         jobject obj,
                                                         jobject pos,
                                                         jint jmodifiers,
                                                         jint allowedOps) {
  CefMouseEvent cef_event;
  if (!GetJNIPoint(env, pos, &cef_event.x, &cef_event.y))
    return;
  cef_event.modifiers = jmodifiers;

  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  browser->GetHost()->DragTargetDragOver(
      cef_event, (CefBrowserHost::DragOperationsMask)allowedOps);
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1DragTargetDragLeave(JNIEnv* env,
                                                          jobject obj) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  browser->GetHost()->DragTargetDragLeave();
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1DragTargetDrop(JNIEnv* env,
                                                     jobject obj,
                                                     jobject pos,
                                                     jint jmodifiers) {
  CefMouseEvent cef_event;
  if (!GetJNIPoint(env, pos, &cef_event.x, &cef_event.y))
    return;
  cef_event.modifiers = jmodifiers;

  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  browser->GetHost()->DragTargetDrop(cef_event);
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1DragSourceEndedAt(JNIEnv* env,
                                                        jobject obj,
                                                        jobject pos,
                                                        jint operation) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  int x, y;
  if (!GetJNIPoint(env, pos, &x, &y))
    return;
  browser->GetHost()->DragSourceEndedAt(
      x, y, (CefBrowserHost::DragOperationsMask)operation);
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1DragSourceSystemDragEnded(JNIEnv* env,
                                                                jobject obj) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  browser->GetHost()->DragSourceSystemDragEnded();
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1UpdateUI(JNIEnv* env,
                                               jobject obj,
                                               jobject jcontentRect,
                                               jobject jbrowserRect) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  CefRect contentRect = GetJNIRect(env, jcontentRect);
#if defined(OS_MACOSX)
  CefRect browserRect = GetJNIRect(env, jbrowserRect);
  util_mac::UpdateView(browser->GetHost()->GetWindowHandle(), contentRect,
                       browserRect);
#else
  CefWindowHandle windowHandle = browser->GetHost()->GetWindowHandle();
  if (CefCurrentlyOn(TID_UI)) {
    util::SetWindowBounds(windowHandle, contentRect);
  } else {
    CefPostTask(TID_UI, base::BindOnce(util::SetWindowBounds, windowHandle,
                                       contentRect));
  }
#endif
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1SetParent(JNIEnv* env,
                                                jobject obj,
                                                jlong windowHandle,
                                                jobject canvas) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  base::OnceClosure callback = base::BindOnce(&OnAfterParentChanged, browser);

#if defined(OS_MACOSX)
  util::SetParent(browser->GetHost()->GetWindowHandle(), windowHandle,
                  std::move(callback));
#else
  CefWindowHandle browserHandle = browser->GetHost()->GetWindowHandle();
  CefWindowHandle parentHandle =
      canvas ? util::GetWindowHandle(env, canvas) : kNullWindowHandle;
  if (CefCurrentlyOn(TID_UI)) {
    util::SetParent(browserHandle, parentHandle, std::move(callback));
  } else {
#if defined(OS_LINUX)
    std::shared_ptr<LinuxUiTaskCompletion> asyncResult = std::make_shared<LinuxUiTaskCompletion>();
    base::OnceClosure completion = base::BindOnce(&OnAfterParentChangedAndSignal, browser, asyncResult);
    if (!CefPostTask(TID_UI, base::BindOnce(util::SetParent, browserHandle, parentHandle, std::move(completion))) || !asyncResult->Wait(std::chrono::seconds(1))) {
      LOG(WARNING) << "Failed or timed out changing browser parent";
    }
#else
    CefPostTask(TID_UI, base::BindOnce(util::SetParent, browserHandle,
                                       parentHandle, std::move(callback)));
#endif
  }
#endif
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1NotifyMoveOrResizeStarted(JNIEnv* env,
                                                                jobject obj) {
#if (defined(OS_WIN) || defined(OS_LINUX))
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, obj);
  if (!browser->GetHost()->IsWindowRenderingDisabled()) {
    browser->GetHost()->NotifyMoveOrResizeStarted();
  }
#endif
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1SetWindowlessFrameRate(JNIEnv* env,
                                                             jobject jbrowser,
                                                             jint frameRate) {
  CefRefPtr<CefBrowser> browser = JNI_GET_BROWSER_OR_RETURN(env, jbrowser);
  CefRefPtr<CefBrowserHost> host = browser->GetHost();
  host->SetWindowlessFrameRate(frameRate);
}

void getWindowlessFrameRate(CefRefPtr<CefBrowserHost> host,
                            CefRefPtr<IntCallback> callback) {
  callback->onComplete((jint)host->GetWindowlessFrameRate());
}

JNIEXPORT void JNICALL
Java_org_cef_browser_CefBrowser_1N_N_1GetWindowlessFrameRate(
    JNIEnv* env,
    jobject jbrowser,
    jobject jintCallback) {
  CefRefPtr<IntCallback> callback = new IntCallback(env, jintCallback);

  CefRefPtr<CefBrowser> browser = GetJNIBrowser(env, jbrowser);
  if (!browser.get()) {
    callback->onComplete(0);
    return;
  }

  CefRefPtr<CefBrowserHost> host = browser->GetHost();
  if (CefCurrentlyOn(TID_UI)) {
    getWindowlessFrameRate(host, callback);
  } else {
    CefPostTask(TID_UI, base::BindOnce(getWindowlessFrameRate, host, callback));
  }
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefBrowser_1N_N_1SetAudioMuted(JNIEnv* env, jobject jbrowser, jboolean muted) {
  CefRefPtr<CefBrowser> browser = GetLifecycleSafeJNIBrowser(env, jbrowser);
  if (!browser.get() || !browser->IsValid())
    return;

  CefRefPtr<CefBrowserHost> host = browser->GetHost();
  if (host.get())
    host->SetAudioMuted(muted != JNI_FALSE);
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefBrowser_1N_N_1IsAudioMuted(JNIEnv* env, jobject jbrowser, jobject jintCallback) {
  startBrowserBooleanQuery(env, jbrowser, jintCallback, BrowserBooleanQuery::kAudioMuted);
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefBrowser_1N_N_1IsFullscreen(JNIEnv* env, jobject jbrowser, jobject jintCallback) {
  startBrowserBooleanQuery(env, jbrowser, jintCallback, BrowserBooleanQuery::kFullscreen);
}

JNIEXPORT void JNICALL Java_org_cef_browser_CefBrowser_1N_N_1ExitFullscreen(JNIEnv* env, jobject jbrowser, jboolean willCauseResize) {
  CefRefPtr<CefBrowser> browser = GetLifecycleSafeJNIBrowser(env, jbrowser);
  if (!browser.get() || !browser->IsValid())
    return;

  CefRefPtr<CefBrowserHost> host = browser->GetHost();
  if (host.get())
    host->ExitFullscreen(willCauseResize != JNI_FALSE);
}
