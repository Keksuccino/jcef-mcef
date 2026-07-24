// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.callback;

import java.util.Objects;

/**
 * Immutable binary data owned by CEF. Instances may be used on any thread and must be released
 * with {@link #dispose()} or {@link #close()}.
 */
public abstract class CefBinaryValue extends CefNativeValue {
    CefBinaryValue() {
        super("CefBinaryValue");
    }

    /** Creates a binary value by copying all bytes in {@code data}. */
    public static CefBinaryValue create(byte[] data) {
        Objects.requireNonNull(data, "data");
        return create(data, 0, data.length);
    }

    /** Creates a binary value by copying {@code length} bytes starting at {@code offset}. */
    public static CefBinaryValue create(byte[] data, int offset, int length) {
        Objects.requireNonNull(data, "data");
        Objects.checkFromIndexSize(offset, length, data.length);
        if (length == 0) throw new IllegalArgumentException("CEF binary values cannot be empty");
        return CefBinaryValue_N.createNative(data, offset, length);
    }

    /** Releases this Java object's native reference. This method is idempotent. */
    @Override
    public final void dispose() {
        disposeNative(this::N_Dispose);
    }

    /** Returns whether the underlying CEF data is valid. A disposed value is invalid. */
    public final boolean isValid() {
        return invokeBooleanIfPresent(this::N_IsValid, false);
    }

    /** Returns whether the underlying data is owned by another CEF value container. */
    public final boolean isOwned() {
        return invokeBoolean(this::N_IsOwned);
    }

    /** Returns whether this value and {@code that} reference the same underlying data. */
    public final boolean isSame(CefBinaryValue that) {
        Objects.requireNonNull(that, "that");
        return invokeBoolean(self -> N_IsSame(self, peerNativeRef(that)));
    }

    /** Returns whether this value and {@code that} contain equivalent data. */
    public final boolean isEqual(CefBinaryValue that) {
        Objects.requireNonNull(that, "that");
        return invokeBoolean(self -> N_IsEqual(self, peerNativeRef(that)));
    }

    /** Returns an independent copy. The returned value must be disposed. */
    public final CefBinaryValue copy() {
        return invokeObject(this::N_Copy);
    }

    /**
     * Returns the number of bytes without narrowing native {@code size_t} to a Java {@code int}.
     */
    public final long getSize() {
        return invokeLong(this::N_GetSize);
    }

    /**
     * Copies at most {@code length} bytes starting at native {@code dataOffset} into
     * {@code buffer} at {@code bufferOffset} and returns the number copied.
     */
    public final int getData(byte[] buffer, int bufferOffset, int length, long dataOffset) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.checkFromIndexSize(bufferOffset, length, buffer.length);
        if (dataOffset < 0) throw new IllegalArgumentException("dataOffset must be non-negative");
        return invokeInt(self -> N_GetData(self, buffer, bufferOffset, length, dataOffset));
    }

    /** Copies bytes starting at {@code dataOffset} into the complete destination array. */
    public final int getData(byte[] buffer, long dataOffset) {
        Objects.requireNonNull(buffer, "buffer");
        return getData(buffer, 0, buffer.length, dataOffset);
    }

    /** Returns a newly allocated copy of all binary data. */
    public final byte[] getData() {
        long size = getSize();
        if (size > Integer.MAX_VALUE)
            throw new ArithmeticException("CEF binary value is too large for a Java byte array: " + size);
        byte[] result = new byte[(int) size];
        int read = getData(result, 0, result.length, 0);
        if (read != result.length)
            throw new IllegalStateException("CEF binary value changed while it was being copied");
        return result;
    }

    protected abstract void N_Dispose(long self);
    protected abstract boolean N_IsValid(long self);
    protected abstract boolean N_IsOwned(long self);
    protected abstract boolean N_IsSame(long self, long that);
    protected abstract boolean N_IsEqual(long self, long that);
    protected abstract CefBinaryValue N_Copy(long self);
    protected abstract long N_GetSize(long self);
    protected abstract int N_GetData(long self, byte[] buffer, int bufferOffset, int length, long dataOffset);
}
