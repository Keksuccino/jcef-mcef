// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.callback;

/**
 * CEF value types. Integer values are pinned to {@code cef_value_type_t} from CEF API 15100 and
 * must not be reordered.
 */
public enum CefValueType {
    VTYPE_INVALID(0),
    VTYPE_NULL(1),
    VTYPE_BOOL(2),
    VTYPE_INT(3),
    VTYPE_DOUBLE(4),
    VTYPE_STRING(5),
    VTYPE_BINARY(6),
    VTYPE_DICTIONARY(7),
    VTYPE_LIST(8),
    /** Native count sentinel; CEF never returns this as the type of a value. */
    VTYPE_NUM_VALUES(9);

    private static final CefValueType[] BY_VALUE = values();
    private final int value_;

    CefValueType(int value) {
        value_ = value;
    }

    /** Returns the exact native {@code cef_value_type_t} integer value. */
    public int getValue() {
        return value_;
    }

    /**
     * Returns the matching Java type. Unknown values from a newer CEF runtime map to
     * {@link #VTYPE_INVALID} so callers fail closed instead of indexing beyond this enum.
     */
    public static CefValueType fromValue(int value) {
        if (value < 0 || value >= BY_VALUE.length || BY_VALUE[value].value_ != value)
            return VTYPE_INVALID;
        return BY_VALUE[value];
    }
}
