// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

/**
 * Browser zoom commands. Integer values are pinned to {@code cef_zoom_command_t} from CEF API
 * 15100 and must not be reordered.
 */
public enum CefZoomCommand {
    /** Decrease the page zoom by one Chromium zoom step. */
    OUT(0),

    /** Reset the page zoom to the browser's configured default level. */
    RESET(1),

    /** Increase the page zoom by one Chromium zoom step. */
    IN(2);

    private static final CefZoomCommand[] BY_VALUE = values();
    private final int value_;

    CefZoomCommand(int value) {
        value_ = value;
    }

    /** Returns the exact native {@code cef_zoom_command_t} integer value. */
    public int getValue() {
        return value_;
    }

    /**
     * Returns the Java zoom command for an exact native CEF API 15100 value.
     *
     * @throws IllegalArgumentException if {@code value} is not a known zoom command
     */
    public static CefZoomCommand fromValue(int value) {
        if (value < 0 || value >= BY_VALUE.length || BY_VALUE[value].value_ != value) {
            throw new IllegalArgumentException("Unknown CEF zoom command: " + value);
        }
        return BY_VALUE[value];
    }
}
