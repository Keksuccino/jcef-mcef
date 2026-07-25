// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.input;

import org.cef.CefColor;
import org.cef.misc.CefRange;

import java.util.Objects;

/**
 * Immutable IME composition underline equivalent to CEF's
 * {@code cef_composition_underline_t}.
 *
 * <p>The packed color values are preserved exactly. The CEF 151 implementation targeted by this
 * bridge documents {@code color} as text color but forwards it to Chromium's
 * suggestion-highlight-color slot in OSR. Callers must not depend on foreground or underline
 * visual color fidelity until that upstream CEF mapping is corrected.
 */
public final class CefCompositionUnderline {
    private final CefRange range_;
    private final CefColor color_;
    private final CefColor backgroundColor_;
    private final boolean thick_;
    private final CefCompositionUnderlineStyle style_;

    /**
     * Creates a composition underline. API calls validate that {@code range} is a valid forward
     * range within the associated composition text's UTF-16 code units.
     *
     * @param range the composition-text range
     * @param color the exact CEF foreground color field
     * @param backgroundColor the exact CEF background color field
     * @param thick whether the underline is thick
     * @param style the underline style
     * @throws NullPointerException if any object argument is {@code null}
     */
    public CefCompositionUnderline(CefRange range, CefColor color, CefColor backgroundColor, boolean thick, CefCompositionUnderlineStyle style) {
        range_ = Objects.requireNonNull(range, "range");
        color_ = Objects.requireNonNull(color, "color");
        backgroundColor_ = Objects.requireNonNull(backgroundColor, "backgroundColor");
        thick_ = thick;
        style_ = Objects.requireNonNull(style, "style");
    }

    /** Returns the composition-text range. */
    public CefRange getRange() {
        return range_;
    }

    /** Returns the exact CEF foreground color field. See the class-level CEF 151 caveat. */
    public CefColor getColor() {
        return color_;
    }

    /** Returns the exact CEF background color field. */
    public CefColor getBackgroundColor() {
        return backgroundColor_;
    }

    /** Returns whether the underline is thick. */
    public boolean isThick() {
        return thick_;
    }

    /** Returns the underline style. */
    public CefCompositionUnderlineStyle getStyle() {
        return style_;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof CefCompositionUnderline)) return false;
        CefCompositionUnderline other = (CefCompositionUnderline) object;
        return thick_ == other.thick_ && range_.equals(other.range_) && color_.equals(other.color_) && backgroundColor_.equals(other.backgroundColor_) && style_ == other.style_;
    }

    @Override
    public int hashCode() {
        int result = range_.hashCode();
        result = 31 * result + color_.hashCode();
        result = 31 * result + backgroundColor_.hashCode();
        result = 31 * result + Boolean.hashCode(thick_);
        return 31 * result + style_.hashCode();
    }

    @Override
    public String toString() {
        return "CefCompositionUnderline{range=" + range_ + ", color=" + color_ + ", backgroundColor=" + backgroundColor_ + ", thick=" + thick_ + ", style=" + style_ + "}";
    }
}
