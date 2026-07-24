// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.misc;

/**
 * Cursor types reported by CEF. The numeric IDs mirror the raw {@code cef_cursor_type_t} values in
 * the supported CEF headers and must stay synchronized with them; they are not AWT
 * predefined-cursor IDs.
 *
 * <p>The declaration order through {@link #CUSTOM} is legacy MCEF API and intentionally remains
 * unchanged. CEF defines two middle-panning variants before {@code CT_CUSTOM} that the legacy Java
 * declaration omitted, so callers must use {@link #getId()} and {@link #fromId(int)} instead of
 * relying on enum ordinals.
 */
public enum CefCursorType {
    POINTER(0, 0),
    CROSS(1, 0x36003), // GLFW_CROSSHAIR_CURSOR
    HAND(2, 0x36004), // GLFW_HAND_CURSOR
    IBEAM(3, 0x36002), // GLFW_IBEAM_CURSOR
    WAIT(4, 0),
    HELP(5, 0),
    EAST_RESIZE(6, 0x36005), // GLFW_RESIZE_EW_CURSOR
    NORTH_RESIZE(7, 0x36006), // GLFW_RESIZE_NS_CURSOR
    NORTH_EAST_RESIZE(8, 0x36008), // GLFW_RESIZE_NESW_CURSOR
    NORTH_WEST_RESIZE(9, 0x36007), // GLFW_RESIZE_NWSE_CURSOR
    SOUTH_RESIZE(10, 0x36006), // GLFW_RESIZE_NS_CURSOR
    SOUTH_EAST_RESIZE(11, 0x36007), // GLFW_RESIZE_NWSE_CURSOR
    SOUTH_WEST_RESIZE(12, 0x36008), // GLFW_RESIZE_NESW_CURSOR
    WEST_RESIZE(13, 0x36005), // GLFW_RESIZE_EW_CURSOR
    NORTH_SOUTH_RESIZE(14, 0x36006), // GLFW_RESIZE_NS_CURSOR
    EAST_WEST_RESIZE(15, 0x36005), // GLFW_RESIZE_EW_CURSOR
    NORTH_EAST_SOUTH_WEST_RESIZE(16, 0x36008), // GLFW_RESIZE_NESW_CURSOR
    NORTH_WEST_SOUTH_EAST_RESIZE(17, 0x36007), // GLFW_RESIZE_NWSE_CURSOR
    COLUMN_RESIZE(18, 0),
    ROW_RESIZE(19, 0),
    MIDDLE_PANNING(20, 0),
    EAST_PANNING(21, 0),
    NORTH_PANNING(22, 0),
    NORTH_EAST_PANNING(23, 0),
    NORTH_WEST_PANNING(24, 0),
    SOUTH_PANNING(25, 0),
    SOUTH_EAST_PANNING(26, 0),
    SOUTH_WEST_PANNING(27, 0),
    WEST_PANNING(28, 0),
    MOVE(29, 0x36009), // GLFW_RESIZE_ALL_CURSOR
    VERTICAL_IBEAM(30, 0), // VERTICAL_TEXT
    CELL(31, 0),
    CONTEXT_MENU(32, 0),
    ALIAS(33, 0),
    PROGRESS(34, 0),
    NO_DROP(35, 0x3600A), // GLFW_NOT_ALLOWED_CURSOR
    COPY(36, 0),
    NONE(37, 0),
    NOT_ALLOWED(38, 0x3600A), // GLFW_NOT_ALLOWED_CURSOR
    ZOOM_IN(39, 0),
    ZOOM_OUT(40, 0),
    GRAB(41, 0),
    GRABBING(42, 0),
    CUSTOM(45, 0),
    MIDDLE_PANNING_VERTICAL(43, 0),
    MIDDLE_PANNING_HORIZONTAL(44, 0),
    DND_NONE(46, 0),
    DND_MOVE(47, 0),
    DND_COPY(48, 0),
    DND_LINK(49, 0),
    UNKNOWN(-1, 0);

    private static final int CEF_CURSOR_TYPE_COUNT = 50;
    private static final CefCursorType[] BY_ID = createById();

    private final int id_;
    /** The compatible GLFW predefined-cursor ID used by MCEF, or zero if unsupported. */
    public final int glfwId;

    CefCursorType(int id, int glfwId) {
        id_ = id;
        this.glfwId = glfwId;
    }

    /** Returns the exact raw {@code cef_cursor_type_t} numeric value, or {@code -1} for UNKNOWN. */
    public int getId() {
        return id_;
    }

    /**
     * Decodes a raw {@code cef_cursor_type_t} value. Invalid values and values introduced by a
     * future CEF runtime safely map to {@link #UNKNOWN}.
     */
    public static CefCursorType fromId(int id) {
        if (id < 0 || id >= BY_ID.length) return UNKNOWN;
        return BY_ID[id];
    }

    private static CefCursorType[] createById() {
        CefCursorType[] byId = new CefCursorType[CEF_CURSOR_TYPE_COUNT];
        for (CefCursorType type : values()) {
            if (type.id_ < 0) continue;
            if (type.id_ >= byId.length || byId[type.id_] != null)
                throw new IllegalStateException("Invalid or duplicate CEF cursor ID: " + type.id_);
            byId[type.id_] = type;
        }
        for (int id = 0; id < byId.length; id++) {
            if (byId[id] == null) throw new IllegalStateException("Missing CEF cursor ID: " + id);
        }
        return byId;
    }
}
