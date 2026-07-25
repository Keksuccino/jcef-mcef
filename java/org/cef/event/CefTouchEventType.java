// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.event;

/**
 * Touch-point states with integer values pinned to {@code cef_touch_event_type_t} from CEF API
 * 15100. Values must not be reordered.
 */
public enum CefTouchEventType {
    /** The contact ended normally. */
    RELEASED(0),

    /** A new contact began. */
    PRESSED(1),

    /** An existing contact changed coordinates. */
    MOVED(2),

    /** The contact stream was cancelled. */
    CANCELLED(3);

    private static final CefTouchEventType[] BY_VALUE = values();
    private final int value_;

    CefTouchEventType(int value) {
        value_ = value;
    }

    /** Returns the exact native {@code cef_touch_event_type_t} integer value. */
    public int getValue() {
        return value_;
    }

    /**
     * Returns the Java touch state for an exact CEF API 15100 value.
     *
     * @throws IllegalArgumentException if {@code value} is not a known touch state
     */
    public static CefTouchEventType fromValue(int value) {
        if (value < 0 || value >= BY_VALUE.length || BY_VALUE[value].value_ != value) {
            throw new IllegalArgumentException("Unknown CEF touch event type: " + value);
        }
        return BY_VALUE[value];
    }
}
