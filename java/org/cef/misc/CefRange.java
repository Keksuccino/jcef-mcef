// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.misc;

/**
 * Immutable character range equivalent to CEF's {@code cef_range_t}. Endpoints use {@code long}
 * because CEF stores the complete unsigned 32-bit domain while Java has no unsigned {@code int}
 * primitive.
 *
 * <p>CEF ranges may be forward, empty, or reversed. The only invalid sentinel is {@link #INVALID}
 * with both endpoints equal to {@link #MAX_VALUE}; a range with just one endpoint equal to
 * {@code MAX_VALUE} remains representable and valid.
 */
public final class CefRange {
    /** Largest endpoint representable by CEF's unsigned 32-bit range fields. */
    public static final long MAX_VALUE = 0xFFFF_FFFFL;

    /** CEF's exact invalid-range sentinel. */
    public static final CefRange INVALID = new CefRange(MAX_VALUE, MAX_VALUE);

    private final long from_;
    private final long to_;

    /**
     * Creates a range without changing endpoint direction.
     *
     * @param from the first unsigned 32-bit endpoint
     * @param to the second unsigned 32-bit endpoint
     * @throws IllegalArgumentException if either endpoint is outside the unsigned 32-bit domain
     */
    public CefRange(long from, long to) {
        from_ = validateEndpoint("from", from);
        to_ = validateEndpoint("to", to);
    }

    /** Returns the first endpoint as an unsigned 32-bit numeric value. */
    public long getFrom() {
        return from_;
    }

    /** Returns the second endpoint as an unsigned 32-bit numeric value. */
    public long getTo() {
        return to_;
    }

    /** Returns {@code false} only for CEF's exact max/max invalid-range sentinel. */
    public boolean isValid() {
        return from_ != MAX_VALUE || to_ != MAX_VALUE;
    }

    /**
     * Returns whether both endpoints are equal. The invalid sentinel is also structurally empty.
     */
    public boolean isEmpty() {
        return from_ == to_;
    }

    /** Returns whether the first endpoint follows the second endpoint. */
    public boolean isReversed() {
        return from_ > to_;
    }

    /** Returns the numerically smaller endpoint without changing the stored direction. */
    public long getMinimum() {
        return Math.min(from_, to_);
    }

    /** Returns the numerically larger endpoint without changing the stored direction. */
    public long getMaximum() {
        return Math.max(from_, to_);
    }

    private static long validateEndpoint(String name, long value) {
        if (value < 0 || value > MAX_VALUE) {
            throw new IllegalArgumentException(name + " must be between 0 and " + MAX_VALUE + ": " + value);
        }
        return value;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof CefRange)) return false;
        CefRange other = (CefRange) object;
        return from_ == other.from_ && to_ == other.to_;
    }

    @Override
    public int hashCode() {
        return 31 * Long.hashCode(from_) + Long.hashCode(to_);
    }

    @Override
    public String toString() {
        return "CefRange{from=" + from_ + ", to=" + to_ + "}";
    }
}
