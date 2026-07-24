// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.callback;

import java.util.Objects;

/**
 * An indexed CEF value list. Instances may be used on any thread and must be released with
 * {@link #dispose()} or {@link #close()}.
 */
public abstract class CefListValue extends CefNativeValue {
    CefListValue() {
        super("CefListValue");
    }

    /** Creates a new writable, empty list. */
    public static CefListValue create() {
        return CefListValue_N.createNative();
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

    public final boolean isReadOnly() {
        return invokeBoolean(this::N_IsReadOnly);
    }

    public final boolean isSame(CefListValue that) {
        Objects.requireNonNull(that, "that");
        return invokeBoolean(self -> N_IsSame(self, peerNativeRef(that)));
    }

    public final boolean isEqual(CefListValue that) {
        Objects.requireNonNull(that, "that");
        return invokeBoolean(self -> N_IsEqual(self, peerNativeRef(that)));
    }

    /** Returns a writable deep copy. */
    public final CefListValue copy() {
        return invokeObject(this::N_Copy);
    }

    /** Resizes this list; newly created entries are native null values. */
    public final boolean setSize(long size) {
        requireNonNegative(size, "size");
        return invokeBoolean(self -> N_SetSize(self, size));
    }

    public final long getSize() {
        return invokeLong(this::N_GetSize);
    }

    public final boolean clear() {
        return invokeBoolean(this::N_Clear);
    }

    public final boolean remove(long index) {
        requireNonNegative(index, "index");
        return invokeBoolean(self -> N_Remove(self, index));
    }

    public final CefValueType getType(long index) {
        requireNonNegative(index, "index");
        return CefValueType.fromValue(invokeInt(self -> N_GetType(self, index)));
    }

    public final CefValue getValue(long index) {
        requireNonNegative(index, "index");
        return invokeObject(self -> N_GetValue(self, index));
    }

    public final boolean getBool(long index) {
        requireNonNegative(index, "index");
        return invokeBoolean(self -> N_GetBool(self, index));
    }

    public final int getInt(long index) {
        requireNonNegative(index, "index");
        return invokeInt(self -> N_GetInt(self, index));
    }

    public final double getDouble(long index) {
        requireNonNegative(index, "index");
        return invokeDouble(self -> N_GetDouble(self, index));
    }

    public final String getString(long index) {
        requireNonNegative(index, "index");
        return invokeObject(self -> N_GetString(self, index));
    }

    public final CefBinaryValue getBinary(long index) {
        requireNonNegative(index, "index");
        return invokeObject(self -> N_GetBinary(self, index));
    }

    public final CefDictionaryValue getDictionary(long index) {
        requireNonNegative(index, "index");
        return invokeObject(self -> N_GetDictionary(self, index));
    }

    public final CefListValue getList(long index) {
        requireNonNegative(index, "index");
        return invokeObject(self -> N_GetList(self, index));
    }

    /**
     * Sets {@code index}, growing the list and filling intermediate entries with null when needed.
     */
    public final boolean setValue(long index, CefValue value) {
        requireNonNegative(index, "index");
        Objects.requireNonNull(value, "value");
        return invokeBoolean(self -> N_SetValue(self, index, peerNativeRef(value)));
    }

    /**
     * Sets {@code index}, growing the list and filling intermediate entries with null if needed.
     */
    public final boolean setNull(long index) {
        requireNonNegative(index, "index");
        return invokeBoolean(self -> N_SetNull(self, index));
    }

    public final boolean setBool(long index, boolean value) {
        requireNonNegative(index, "index");
        return invokeBoolean(self -> N_SetBool(self, index, value));
    }

    public final boolean setInt(long index, int value) {
        requireNonNegative(index, "index");
        return invokeBoolean(self -> N_SetInt(self, index, value));
    }

    public final boolean setDouble(long index, double value) {
        requireNonNegative(index, "index");
        return invokeBoolean(self -> N_SetDouble(self, index, value));
    }

    public final boolean setString(long index, String value) {
        requireNonNegative(index, "index");
        Objects.requireNonNull(value, "value");
        return invokeBoolean(self -> N_SetString(self, index, value));
    }

    /**
     * Assigns binary data using CEF transfer semantics. An unowned source is invalidated on
     * success; an already-owned source is copied.
     */
    public final boolean setBinary(long index, CefBinaryValue value) {
        requireNonNegative(index, "index");
        Objects.requireNonNull(value, "value");
        return invokeBoolean(self -> N_SetBinary(self, index, peerNativeRef(value)));
    }

    /**
     * Assigns a dictionary using CEF transfer semantics. An unowned source is invalidated on
     * success; an already-owned source is copied.
     */
    public final boolean setDictionary(long index, CefDictionaryValue value) {
        requireNonNegative(index, "index");
        Objects.requireNonNull(value, "value");
        return invokeBoolean(self -> N_SetDictionary(self, index, peerNativeRef(value)));
    }

    /**
     * Assigns a list using CEF transfer semantics. An unowned source is invalidated on success; an
     * already-owned source is copied.
     */
    public final boolean setList(long index, CefListValue value) {
        requireNonNegative(index, "index");
        Objects.requireNonNull(value, "value");
        return invokeBoolean(self -> N_SetList(self, index, peerNativeRef(value)));
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must be non-negative");
    }

    protected abstract void N_Dispose(long self);
    protected abstract boolean N_IsValid(long self);
    protected abstract boolean N_IsOwned(long self);
    protected abstract boolean N_IsReadOnly(long self);
    protected abstract boolean N_IsSame(long self, long that);
    protected abstract boolean N_IsEqual(long self, long that);
    protected abstract CefListValue N_Copy(long self);
    protected abstract boolean N_SetSize(long self, long size);
    protected abstract long N_GetSize(long self);
    protected abstract boolean N_Clear(long self);
    protected abstract boolean N_Remove(long self, long index);
    protected abstract int N_GetType(long self, long index);
    protected abstract CefValue N_GetValue(long self, long index);
    protected abstract boolean N_GetBool(long self, long index);
    protected abstract int N_GetInt(long self, long index);
    protected abstract double N_GetDouble(long self, long index);
    protected abstract String N_GetString(long self, long index);
    protected abstract CefBinaryValue N_GetBinary(long self, long index);
    protected abstract CefDictionaryValue N_GetDictionary(long self, long index);
    protected abstract CefListValue N_GetList(long self, long index);
    protected abstract boolean N_SetValue(long self, long index, long value);
    protected abstract boolean N_SetNull(long self, long index);
    protected abstract boolean N_SetBool(long self, long index, boolean value);
    protected abstract boolean N_SetInt(long self, long index, int value);
    protected abstract boolean N_SetDouble(long self, long index, double value);
    protected abstract boolean N_SetString(long self, long index, String value);
    protected abstract boolean N_SetBinary(long self, long index, long value);
    protected abstract boolean N_SetDictionary(long self, long index, long value);
    protected abstract boolean N_SetList(long self, long index, long value);
}
