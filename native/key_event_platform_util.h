// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#ifndef JCEF_NATIVE_KEY_EVENT_PLATFORM_UTIL_H_
#define JCEF_NATIVE_KEY_EVENT_PLATFORM_UTIL_H_

#include <cstdint>

namespace key_event_platform_util {

enum class InputEventSemantics { kAwt, kGlfw };

// Resolves the Linux XKB hardware keycode expected by Chromium. A valid Java
// rawCode/scancode is authoritative; otherwise the physical identity is
// inferred deterministically from the AWT or GLFW key domain.
int ResolveLinuxNativeKeyCode(std::int64_t supplied_native_key_code, int key_code, int key_location, bool typed, InputEventSemantics semantics);

// Resolves the Windows OEM scan code expected by Chromium. MapVirtualKey's
// fallback and Java/GLFW-supplied scan codes use multiple representations for
// extended keys; this function normalizes them without discarding authoritative
// E0/E1-prefixed values supplied by the input toolkit.
int ResolveWindowsNativeKeyCode(std::int64_t supplied_scan_code, std::uint32_t mapped_scan_code, bool extended);

}  // namespace key_event_platform_util

#endif  // JCEF_NATIVE_KEY_EVENT_PLATFORM_UTIL_H_
