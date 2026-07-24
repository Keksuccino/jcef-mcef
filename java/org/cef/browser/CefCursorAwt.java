// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

import org.cef.misc.CefCursorType;

import java.awt.Cursor;

/** Maps raw CEF cursor IDs to the predefined AWT cursors historically selected by JCEF. */
final class CefCursorAwt {
    private CefCursorAwt() {}

    static Cursor fromCefId(int cursorId) {
        return Cursor.getPredefinedCursor(toAwtCursorType(CefCursorType.fromId(cursorId)));
    }

    private static int toAwtCursorType(CefCursorType cursorType) {
        switch (cursorType) {
            case CROSS:
                return Cursor.CROSSHAIR_CURSOR;
            case HAND:
                return Cursor.HAND_CURSOR;
            case IBEAM:
                return Cursor.TEXT_CURSOR;
            case WAIT:
                return Cursor.WAIT_CURSOR;
            case EAST_RESIZE:
                return Cursor.E_RESIZE_CURSOR;
            case NORTH_RESIZE:
                return Cursor.N_RESIZE_CURSOR;
            case NORTH_EAST_RESIZE:
                return Cursor.NE_RESIZE_CURSOR;
            case NORTH_WEST_RESIZE:
                return Cursor.NW_RESIZE_CURSOR;
            case SOUTH_RESIZE:
                return Cursor.S_RESIZE_CURSOR;
            case SOUTH_EAST_RESIZE:
                return Cursor.SE_RESIZE_CURSOR;
            case SOUTH_WEST_RESIZE:
                return Cursor.SW_RESIZE_CURSOR;
            case WEST_RESIZE:
                return Cursor.W_RESIZE_CURSOR;
            case MOVE:
                return Cursor.MOVE_CURSOR;
            default:
                return Cursor.DEFAULT_CURSOR;
        }
    }
}
