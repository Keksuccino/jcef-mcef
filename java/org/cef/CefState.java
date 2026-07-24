// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef;

/**
 * Represents the state of a CEF setting. Integer values are pinned to {@code cef_state_t} from CEF
 * API 15100 and must not be reordered.
 */
public enum CefState {
    /** Use the default state for the setting. */
    DEFAULT(0),

    /** Enable or allow the setting. */
    ENABLED(1),

    /** Disable or disallow the setting. */
    DISABLED(2);

    private static final CefState[] BY_VALUE = values();
    private final int value_;

    CefState(int value) {
        value_ = value;
    }

    /**
     * Returns the exact native {@code cef_state_t} integer value.
     *
     * @return the CEF API 15100 value
     */
    public int getValue() {
        return value_;
    }

    /**
     * Returns the Java state for an exact native CEF API 15100 value.
     *
     * @param value the native integer value
     * @return the matching Java state
     * @throws IllegalArgumentException if {@code value} is not a known {@code cef_state_t} value
     */
    public static CefState fromValue(int value) {
        if (value < 0 || value >= BY_VALUE.length || BY_VALUE[value].value_ != value) {
            throw new IllegalArgumentException("Unknown CEF state: " + value);
        }
        return BY_VALUE[value];
    }
}
