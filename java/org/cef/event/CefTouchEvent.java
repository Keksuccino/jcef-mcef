// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.event;

import org.cef.misc.EventFlags;

import java.util.Objects;

/**
 * Immutable touch-point snapshot equivalent to CEF 151's {@code cef_touch_event_t}.
 *
 * <p>The X and Y coordinates use logical view coordinates. CEF defines radii only as pixel values
 * and does not state that they share the X/Y logical-coordinate conversion. The bridge forwards
 * all fields unchanged except for an internal rotation-unit compatibility conversion required by
 * the pinned CEF runtime; it does not apply device scaling, clamp metadata, reorder contacts, or
 * repair event sequences. Callers always supply the CEF-documented radians value.
 */
public final class CefTouchEvent {
    private static final float ROTATION_ANGLE_LIMIT = (float) Math.PI;
    private static final int KNOWN_MODIFIERS_MASK = EventFlags.EVENTFLAG_CAPS_LOCK_ON
            | EventFlags.EVENTFLAG_SHIFT_DOWN | EventFlags.EVENTFLAG_CONTROL_DOWN
            | EventFlags.EVENTFLAG_ALT_DOWN | EventFlags.EVENTFLAG_LEFT_MOUSE_BUTTON
            | EventFlags.EVENTFLAG_MIDDLE_MOUSE_BUTTON | EventFlags.EVENTFLAG_RIGHT_MOUSE_BUTTON
            | EventFlags.EVENTFLAG_COMMAND_DOWN | EventFlags.EVENTFLAG_NUM_LOCK_ON
            | EventFlags.EVENTFLAG_IS_KEY_PAD | EventFlags.EVENTFLAG_IS_LEFT
            | EventFlags.EVENTFLAG_IS_RIGHT | EventFlags.EVENTFLAG_ALTGR_DOWN
            | EventFlags.EVENTFLAG_IS_REPEAT | EventFlags.EVENTFLAG_PRECISION_SCROLLING_DELTA
            | EventFlags.EVENTFLAG_SCROLL_BY_PAGE;

    private final int id_;
    private final float x_;
    private final float y_;
    private final float radiusX_;
    private final float radiusY_;
    private final float rotationAngle_;
    private final float pressure_;
    private final CefTouchEventType type_;
    private final int modifiers_;
    private final CefPointerType pointerType_;

    /**
     * Creates one complete CEF touch-point snapshot.
     *
     * @param id contact identifier, unique among active contacts and never {@code -1}
     * @param x X coordinate relative to the left side of the logical view
     * @param y Y coordinate relative to the top side of the logical view
     * @param radiusX non-negative CEF X radius in pixels, or zero when not applicable
     * @param radiusY non-negative CEF Y radius in pixels, or zero when not applicable
     * @param rotationAngle rotation angle in radians from zero inclusive to pi exclusive, or zero
     *        when not applicable
     * @param pressure normalized pressure from zero through one, or zero when not applicable
     * @param type contact state
     * @param modifiers bitwise combination of the values in {@link EventFlags}
     * @param pointerType device type that produced the event
     * @throws NullPointerException if {@code type} or {@code pointerType} is {@code null}
     * @throws IllegalArgumentException if the identifier, floating-point metadata, radius,
     *         pressure, or modifier mask is outside CEF's accepted domain
     */
    public CefTouchEvent(int id, float x, float y, float radiusX, float radiusY, float rotationAngle, float pressure, CefTouchEventType type, int modifiers, CefPointerType pointerType) {
        if (id == -1) throw new IllegalArgumentException("id must not be -1");
        id_ = id;
        x_ = requireFinite("x", x);
        y_ = requireFinite("y", y);
        radiusX_ = requireRadius("radiusX", radiusX);
        radiusY_ = requireRadius("radiusY", radiusY);
        rotationAngle_ = requireRotationAngle(rotationAngle);
        pressure_ = requirePressure(pressure);
        type_ = Objects.requireNonNull(type, "type");
        if ((modifiers & ~KNOWN_MODIFIERS_MASK) != 0) throw new IllegalArgumentException("modifiers contains unknown CEF event flag bits: 0x" + Integer.toHexString(modifiers));
        modifiers_ = modifiers;
        pointerType_ = Objects.requireNonNull(pointerType, "pointerType");
    }

    /**
     * Creates a touch contact with zero radii, rotation, pressure, and modifiers using the {@link
     * CefPointerType#TOUCH TOUCH} pointer type.
     *
     * @param id contact identifier, unique among active contacts and never {@code -1}
     * @param x X coordinate relative to the left side of the logical view
     * @param y Y coordinate relative to the top side of the logical view
     * @param type contact state
     */
    public CefTouchEvent(int id, float x, float y, CefTouchEventType type) {
        this(id, x, y, 0.0f, 0.0f, 0.0f, 0.0f, type, EventFlags.EVENTFLAG_NONE, CefPointerType.TOUCH);
    }

    /** Returns the contact identifier. */
    public int getId() {
        return id_;
    }

    /** Returns the logical X coordinate relative to the view. */
    public float getX() {
        return x_;
    }

    /** Returns the logical Y coordinate relative to the view. */
    public float getY() {
        return y_;
    }

    /** Returns the non-negative CEF X radius in pixels. */
    public float getRadiusX() {
        return radiusX_;
    }

    /** Returns the non-negative CEF Y radius in pixels. */
    public float getRadiusY() {
        return radiusY_;
    }

    /** Returns the rotation angle in radians. */
    public float getRotationAngle() {
        return rotationAngle_;
    }

    /** Returns normalized pressure from zero through one. */
    public float getPressure() {
        return pressure_;
    }

    /** Returns the contact state. */
    public CefTouchEventType getType() {
        return type_;
    }

    /** Returns the exact CEF event-flag mask. */
    public int getModifiers() {
        return modifiers_;
    }

    /** Returns the pointer device type. */
    public CefPointerType getPointerType() {
        return pointerType_;
    }

    private static float requireFinite(String name, float value) {
        if (!Float.isFinite(value))
            throw new IllegalArgumentException(name + " must be finite: " + value);
        return value;
    }

    private static float requireRadius(String name, float value) {
        requireFinite(name, value);
        if (value < 0.0f)
            throw new IllegalArgumentException(name + " must not be negative: " + value);
        return value;
    }

    private static float requirePressure(float value) {
        requireFinite("pressure", value);
        if (value < 0.0f || value > 1.0f)
            throw new IllegalArgumentException("pressure must be between 0 and 1: " + value);
        return value;
    }

    private static float requireRotationAngle(float value) {
        requireFinite("rotationAngle", value);
        if (value < 0.0f || value >= ROTATION_ANGLE_LIMIT)
            throw new IllegalArgumentException("rotationAngle must be at least 0 and less than pi radians: " + value);
        return value;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof CefTouchEvent)) return false;
        CefTouchEvent other = (CefTouchEvent) object;
        return id_ == other.id_ && Float.compare(x_, other.x_) == 0
                && Float.compare(y_, other.y_) == 0 && Float.compare(radiusX_, other.radiusX_) == 0
                && Float.compare(radiusY_, other.radiusY_) == 0
                && Float.compare(rotationAngle_, other.rotationAngle_) == 0
                && Float.compare(pressure_, other.pressure_) == 0 && type_ == other.type_
                && modifiers_ == other.modifiers_ && pointerType_ == other.pointerType_;
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(id_);
        result = 31 * result + Float.hashCode(x_);
        result = 31 * result + Float.hashCode(y_);
        result = 31 * result + Float.hashCode(radiusX_);
        result = 31 * result + Float.hashCode(radiusY_);
        result = 31 * result + Float.hashCode(rotationAngle_);
        result = 31 * result + Float.hashCode(pressure_);
        result = 31 * result + type_.hashCode();
        result = 31 * result + Integer.hashCode(modifiers_);
        return 31 * result + pointerType_.hashCode();
    }

    @Override
    public String toString() {
        return "CefTouchEvent{id=" + id_ + ", x=" + x_ + ", y=" + y_ + ", radiusX=" + radiusX_
                + ", radiusY=" + radiusY_ + ", rotationAngle=" + rotationAngle_
                + ", pressure=" + pressure_ + ", type=" + type_ + ", modifiers=0x"
                + Integer.toHexString(modifiers_) + ", pointerType=" + pointerType_ + "}";
    }
}
