// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

/**
 * Values accepted by CEF content settings. Integer values are pinned to CEF API 15100 and must not
 * be reordered.
 */
public enum CefContentSettingValue {
    DEFAULT(0),
    ALLOW(1),
    BLOCK(2),
    ASK(3),
    SESSION_ONLY(4),
    DETECT_IMPORTANT_CONTENT_DEPRECATED(5);

    private static final CefContentSettingValue[] BY_VALUE = values();
    private final int value_;

    CefContentSettingValue(int value) {
        value_ = value;
    }

    /** Returns the exact {@code cef_content_setting_values_t} integer value. */
    public int getValue() {
        return value_;
    }

    /** Returns the Java value for an exact native CEF API 15100 value. */
    public static CefContentSettingValue fromValue(int value) {
        if (value < 0 || value >= BY_VALUE.length || BY_VALUE[value].value_ != value) {
            throw new IllegalArgumentException("Unknown CEF content setting value: " + value);
        }
        return BY_VALUE[value];
    }
}
