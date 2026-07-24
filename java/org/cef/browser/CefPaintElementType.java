// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

/**
 * Identifies a windowless browser surface that can be invalidated. Integer values are pinned to
 * {@code cef_paint_element_type_t} from CEF API 15100 and must not be reordered.
 */
public enum CefPaintElementType {
    /** The main browser view. */
    PET_VIEW(0),

    /** A popup widget rendered above the main view. */
    PET_POPUP(1);

    private static final CefPaintElementType[] BY_VALUE = values();
    private final int value_;

    CefPaintElementType(int value) {
        value_ = value;
    }

    /** Returns the exact native {@code cef_paint_element_type_t} integer value. */
    public int getValue() {
        return value_;
    }

    /**
     * Returns the Java paint element type for an exact native CEF API 15100 value.
     *
     * @throws IllegalArgumentException if {@code value} is not a known paint element type
     */
    public static CefPaintElementType fromValue(int value) {
        if (value < 0 || value >= BY_VALUE.length || BY_VALUE[value].value_ != value) {
            throw new IllegalArgumentException("Unknown CEF paint element type: " + value);
        }
        return BY_VALUE[value];
    }
}
