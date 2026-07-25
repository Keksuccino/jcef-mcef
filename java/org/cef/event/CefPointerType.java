// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.event;

/**
 * Pointer device types with integer values pinned to {@code cef_pointer_type_t} from CEF API
 * 15100. Values must not be reordered.
 */
public enum CefPointerType {
    /** A direct touch contact. */
    TOUCH(0),

    /** A mouse-like pointer. */
    MOUSE(1),

    /** A pen or stylus tip. */
    PEN(2),

    /** A stylus eraser. */
    ERASER(3),

    /** A pointer whose device type is not known. */
    UNKNOWN(4);

    private static final CefPointerType[] BY_VALUE = values();
    private final int value_;

    CefPointerType(int value) {
        value_ = value;
    }

    /** Returns the exact native {@code cef_pointer_type_t} integer value. */
    public int getValue() {
        return value_;
    }

    /**
     * Returns the Java pointer type for an exact CEF API 15100 value.
     *
     * @throws IllegalArgumentException if {@code value} is not a known pointer type
     */
    public static CefPointerType fromValue(int value) {
        if (value < 0 || value >= BY_VALUE.length || BY_VALUE[value].value_ != value) {
            throw new IllegalArgumentException("Unknown CEF pointer type: " + value);
        }
        return BY_VALUE[value];
    }
}
