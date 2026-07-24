// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

/**
 * Chrome color scheme variants. Integer values are pinned to CEF API 15100 and must not be
 * reordered.
 */
public enum CefColorVariant {
    SYSTEM(0),
    LIGHT(1),
    DARK(2),
    TONAL_SPOT(3),
    NEUTRAL(4),
    VIBRANT(5),
    EXPRESSIVE(6);

    private static final CefColorVariant[] BY_VALUE = values();
    private final int value_;

    CefColorVariant(int value) {
        value_ = value;
    }

    /** Returns the exact {@code cef_color_variant_t} integer value. */
    public int getValue() {
        return value_;
    }

    /** Returns the Java variant for an exact native CEF API 15100 value. */
    public static CefColorVariant fromValue(int value) {
        if (value < 0 || value >= BY_VALUE.length || BY_VALUE[value].value_ != value) {
            throw new IllegalArgumentException("Unknown CEF color variant: " + value);
        }
        return BY_VALUE[value];
    }
}
