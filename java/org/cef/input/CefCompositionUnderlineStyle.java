// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.input;

/** IME composition underline styles with values pinned to CEF API 15100. */
public enum CefCompositionUnderlineStyle {
    SOLID(0),
    DOT(1),
    DASH(2),
    NONE(3);

    private final int value_;

    CefCompositionUnderlineStyle(int value) {
        value_ = value;
    }

    /** Returns the exact {@code cef_composition_underline_style_t} integer value. */
    public int getValue() {
        return value_;
    }

    /**
     * Returns the Java style for an exact CEF API 15100 value.
     *
     * @throws IllegalArgumentException if {@code value} is not a public composition style
     */
    public static CefCompositionUnderlineStyle fromValue(int value) {
        switch (value) {
            case 0:
                return SOLID;
            case 1:
                return DOT;
            case 2:
                return DASH;
            case 3:
                return NONE;
            default:
                throw new IllegalArgumentException("Unknown CEF composition underline style: " + value);
        }
    }
}
