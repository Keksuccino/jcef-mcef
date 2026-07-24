// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.callback;

import java.util.Objects;

/**
 * Wraps a CEF value. Complex values are referenced using CEF's native ownership rules. Instances
 * may be used on any thread and must be released with {@link #dispose()} or {@link #close()}.
 */
public abstract class CefValue extends CefNativeValue {
    CefValue() {
        super("CefValue");
    }

    /** Creates a new value whose initial type is {@link CefValueType#VTYPE_NULL}. */
    public static CefValue create() {
        return CefValue_N.createNative();
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

    /** Returns whether the underlying data is read-only. */
    public final boolean isReadOnly() {
        return invokeBoolean(this::N_IsReadOnly);
    }

    /** Returns whether this value and {@code that} reference the same underlying data. */
    public final boolean isSame(CefValue that) {
        Objects.requireNonNull(that, "that");
        return invokeBoolean(self -> N_IsSame(self, peerNativeRef(that)));
    }

    /** Returns whether this value and {@code that} contain equivalent data. */
    public final boolean isEqual(CefValue that) {
        Objects.requireNonNull(that, "that");
        return invokeBoolean(self -> N_IsEqual(self, peerNativeRef(that)));
    }

    /** Returns an independent deep copy. The returned value must be disposed. */
    public final CefValue copy() {
        return invokeObject(this::N_Copy);
    }

    /** Returns the current value type. */
    public final CefValueType getType() {
        return CefValueType.fromValue(invokeInt(this::N_GetType));
    }

    public final boolean getBool() {
        return invokeBoolean(this::N_GetBool);
    }

    public final int getInt() {
        return invokeInt(this::N_GetInt);
    }

    public final double getDouble() {
        return invokeDouble(this::N_GetDouble);
    }

    public final String getString() {
        return invokeObject(this::N_GetString);
    }

    /** Returns an independently reference-counted Java bridge to the binary data, or {@code null}. */
    public final CefBinaryValue getBinary() {
        return invokeObject(this::N_GetBinary);
    }

    /** Returns an independently reference-counted Java bridge to the dictionary data, or {@code null}. */
    public final CefDictionaryValue getDictionary() {
        return invokeObject(this::N_GetDictionary);
    }

    /** Returns an independently reference-counted Java bridge to the list data, or {@code null}. */
    public final CefListValue getList() {
        return invokeObject(this::N_GetList);
    }

    /**
     * Sets this value to native null. This can reuse a value whose complex data was invalidated.
     */
    public final boolean setNull() {
        return invokeBoolean(this::N_SetNull);
    }

    public final boolean setBool(boolean value) {
        return invokeBoolean(self -> N_SetBool(self, value));
    }

    public final boolean setInt(int value) {
        return invokeBoolean(self -> N_SetInt(self, value));
    }

    public final boolean setDouble(double value) {
        return invokeBoolean(self -> N_SetDouble(self, value));
    }

    public final boolean setString(String value) {
        Objects.requireNonNull(value, "value");
        return invokeBoolean(self -> N_SetString(self, value));
    }

    /** Keeps a reference to {@code value}; CEF does not transfer its ownership here. */
    public final boolean setBinary(CefBinaryValue value) {
        Objects.requireNonNull(value, "value");
        return invokeBoolean(self -> N_SetBinary(self, peerNativeRef(value)));
    }

    /** Keeps a reference to {@code value}; CEF does not transfer its ownership here. */
    public final boolean setDictionary(CefDictionaryValue value) {
        Objects.requireNonNull(value, "value");
        return invokeBoolean(self -> N_SetDictionary(self, peerNativeRef(value)));
    }

    /** Keeps a reference to {@code value}; CEF does not transfer its ownership here. */
    public final boolean setList(CefListValue value) {
        Objects.requireNonNull(value, "value");
        return invokeBoolean(self -> N_SetList(self, peerNativeRef(value)));
    }

    protected abstract void N_Dispose(long self);
    protected abstract boolean N_IsValid(long self);
    protected abstract boolean N_IsOwned(long self);
    protected abstract boolean N_IsReadOnly(long self);
    protected abstract boolean N_IsSame(long self, long that);
    protected abstract boolean N_IsEqual(long self, long that);
    protected abstract CefValue N_Copy(long self);
    protected abstract int N_GetType(long self);
    protected abstract boolean N_GetBool(long self);
    protected abstract int N_GetInt(long self);
    protected abstract double N_GetDouble(long self);
    protected abstract String N_GetString(long self);
    protected abstract CefBinaryValue N_GetBinary(long self);
    protected abstract CefDictionaryValue N_GetDictionary(long self);
    protected abstract CefListValue N_GetList(long self);
    protected abstract boolean N_SetNull(long self);
    protected abstract boolean N_SetBool(long self, boolean value);
    protected abstract boolean N_SetInt(long self, int value);
    protected abstract boolean N_SetDouble(long self, double value);
    protected abstract boolean N_SetString(long self, String value);
    protected abstract boolean N_SetBinary(long self, long value);
    protected abstract boolean N_SetDictionary(long self, long value);
    protected abstract boolean N_SetList(long self, long value);
}
