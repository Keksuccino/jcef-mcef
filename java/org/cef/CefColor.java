// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef;

/**
 * Immutable 32-bit ARGB color value equivalent to CEF's {@code cef_color_t}. Components are stored
 * in alpha, red, green, blue order and are not premultiplied.
 */
public final class CefColor {
    private final int argb_;

    /**
     * Creates a color from unsigned 8-bit components.
     *
     * @param alpha the alpha component
     * @param red the red component
     * @param green the green component
     * @param blue the blue component
     * @throws IllegalArgumentException if any component is outside the range 0 through 255
     */
    public CefColor(int alpha, int red, int green, int blue) {
        validateComponent("alpha", alpha);
        validateComponent("red", red);
        validateComponent("green", green);
        validateComponent("blue", blue);
        argb_ = (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private CefColor(int argb) {
        argb_ = argb;
    }

    /**
     * Creates a color that preserves every bit of the supplied packed ARGB value. All alpha values
     * are accepted here; APIs with alpha restrictions validate those restrictions at their call
     * boundary.
     *
     * @param packed the exact 32-bit ARGB bit pattern
     * @return the corresponding immutable color
     */
    public static CefColor fromArgb(int packed) {
        return new CefColor(packed);
    }

    /**
     * Returns the alpha component.
     *
     * @return an unsigned value from 0 through 255
     */
    public int getAlpha() {
        return (argb_ >>> 24) & 0xFF;
    }

    /**
     * Returns the red component.
     *
     * @return an unsigned value from 0 through 255
     */
    public int getRed() {
        return (argb_ >>> 16) & 0xFF;
    }

    /**
     * Returns the green component.
     *
     * @return an unsigned value from 0 through 255
     */
    public int getGreen() {
        return (argb_ >>> 8) & 0xFF;
    }

    /**
     * Returns the blue component.
     *
     * @return an unsigned value from 0 through 255
     */
    public int getBlue() {
        return argb_ & 0xFF;
    }

    /**
     * Returns the exact packed 32-bit ARGB bit pattern. Java represents this pattern with a signed
     * {@code int}; use {@link Integer#toUnsignedLong(int)} when an unsigned numeric representation
     * is needed.
     *
     * @return the exact packed bits
     */
    public int getArgb() {
        return argb_;
    }

    private static void validateComponent(String name, int value) {
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException(name + " must be between 0 and 255: " + value);
        }
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof CefColor)) return false;
        CefColor other = (CefColor) object;
        return argb_ == other.argb_;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(argb_);
    }

    @Override
    public String toString() {
        return String.format("CefColor{argb=0x%08X}", Integer.toUnsignedLong(argb_));
    }
}
