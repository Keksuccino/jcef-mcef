// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.callback;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A string-keyed CEF value dictionary. Instances may be used on any thread and must be released
 * with {@link #dispose()} or {@link #close()}.
 */
public abstract class CefDictionaryValue extends CefNativeValue {
    CefDictionaryValue() {
        super("CefDictionaryValue");
    }

    /** Creates a new writable, empty dictionary. */
    public static CefDictionaryValue create() {
        return CefDictionaryValue_N.createNative();
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

    public final boolean isSame(CefDictionaryValue that) {
        Objects.requireNonNull(that, "that");
        return invokeBoolean(self -> N_IsSame(self, peerNativeRef(that)));
    }

    public final boolean isEqual(CefDictionaryValue that) {
        Objects.requireNonNull(that, "that");
        return invokeBoolean(self -> N_IsEqual(self, peerNativeRef(that)));
    }

    /** Returns a writable deep copy, optionally omitting empty dictionaries and lists. */
    public final CefDictionaryValue copy(boolean excludeEmptyChildren) {
        return invokeObject(self -> N_Copy(self, excludeEmptyChildren));
    }

    public final long getSize() {
        return invokeLong(this::N_GetSize);
    }

    public final boolean clear() {
        return invokeBoolean(this::N_Clear);
    }

    public final boolean hasKey(String key) {
        Objects.requireNonNull(key, "key");
        return invokeBoolean(self -> N_HasKey(self, key));
    }

    /** Returns an immutable snapshot of all keys in CEF's native iteration order. */
    public final List<String> getKeys() {
        String[] keys = invokeObject(this::N_GetKeys);
        return Collections.unmodifiableList(Arrays.asList(keys));
    }

    public final boolean remove(String key) {
        Objects.requireNonNull(key, "key");
        return invokeBoolean(self -> N_Remove(self, key));
    }

    public final CefValueType getType(String key) {
        Objects.requireNonNull(key, "key");
        return CefValueType.fromValue(invokeInt(self -> N_GetType(self, key)));
    }

    public final CefValue getValue(String key) {
        Objects.requireNonNull(key, "key");
        return invokeObject(self -> N_GetValue(self, key));
    }

    public final boolean getBool(String key) {
        Objects.requireNonNull(key, "key");
        return invokeBoolean(self -> N_GetBool(self, key));
    }

    public final int getInt(String key) {
        Objects.requireNonNull(key, "key");
        return invokeInt(self -> N_GetInt(self, key));
    }

    public final double getDouble(String key) {
        Objects.requireNonNull(key, "key");
        return invokeDouble(self -> N_GetDouble(self, key));
    }

    public final String getString(String key) {
        Objects.requireNonNull(key, "key");
        return invokeObject(self -> N_GetString(self, key));
    }

    public final CefBinaryValue getBinary(String key) {
        Objects.requireNonNull(key, "key");
        return invokeObject(self -> N_GetBinary(self, key));
    }

    public final CefDictionaryValue getDictionary(String key) {
        Objects.requireNonNull(key, "key");
        return invokeObject(self -> N_GetDictionary(self, key));
    }

    public final CefListValue getList(String key) {
        Objects.requireNonNull(key, "key");
        return invokeObject(self -> N_GetList(self, key));
    }

    public final boolean setValue(String key, CefValue value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        return invokeBoolean(self -> N_SetValue(self, key, peerNativeRef(value)));
    }

    public final boolean setNull(String key) {
        Objects.requireNonNull(key, "key");
        return invokeBoolean(self -> N_SetNull(self, key));
    }

    public final boolean setBool(String key, boolean value) {
        Objects.requireNonNull(key, "key");
        return invokeBoolean(self -> N_SetBool(self, key, value));
    }

    public final boolean setInt(String key, int value) {
        Objects.requireNonNull(key, "key");
        return invokeBoolean(self -> N_SetInt(self, key, value));
    }

    public final boolean setDouble(String key, double value) {
        Objects.requireNonNull(key, "key");
        return invokeBoolean(self -> N_SetDouble(self, key, value));
    }

    public final boolean setString(String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        return invokeBoolean(self -> N_SetString(self, key, value));
    }

    /**
     * Assigns binary data using CEF transfer semantics. An unowned source is invalidated on
     * success; an already-owned source is copied.
     */
    public final boolean setBinary(String key, CefBinaryValue value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        return invokeBoolean(self -> N_SetBinary(self, key, peerNativeRef(value)));
    }

    /**
     * Assigns a dictionary using CEF transfer semantics. An unowned source is invalidated on
     * success; an already-owned source is copied.
     */
    public final boolean setDictionary(String key, CefDictionaryValue value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        return invokeBoolean(self -> N_SetDictionary(self, key, peerNativeRef(value)));
    }

    /**
     * Assigns a list using CEF transfer semantics. An unowned source is invalidated on success; an
     * already-owned source is copied.
     */
    public final boolean setList(String key, CefListValue value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        return invokeBoolean(self -> N_SetList(self, key, peerNativeRef(value)));
    }

    protected abstract void N_Dispose(long self);
    protected abstract boolean N_IsValid(long self);
    protected abstract boolean N_IsOwned(long self);
    protected abstract boolean N_IsReadOnly(long self);
    protected abstract boolean N_IsSame(long self, long that);
    protected abstract boolean N_IsEqual(long self, long that);
    protected abstract CefDictionaryValue N_Copy(long self, boolean excludeEmptyChildren);
    protected abstract long N_GetSize(long self);
    protected abstract boolean N_Clear(long self);
    protected abstract boolean N_HasKey(long self, String key);
    protected abstract String[] N_GetKeys(long self);
    protected abstract boolean N_Remove(long self, String key);
    protected abstract int N_GetType(long self, String key);
    protected abstract CefValue N_GetValue(long self, String key);
    protected abstract boolean N_GetBool(long self, String key);
    protected abstract int N_GetInt(long self, String key);
    protected abstract double N_GetDouble(long self, String key);
    protected abstract String N_GetString(long self, String key);
    protected abstract CefBinaryValue N_GetBinary(long self, String key);
    protected abstract CefDictionaryValue N_GetDictionary(long self, String key);
    protected abstract CefListValue N_GetList(long self, String key);
    protected abstract boolean N_SetValue(long self, String key, long value);
    protected abstract boolean N_SetNull(long self, String key);
    protected abstract boolean N_SetBool(long self, String key, boolean value);
    protected abstract boolean N_SetInt(long self, String key, int value);
    protected abstract boolean N_SetDouble(long self, String key, double value);
    protected abstract boolean N_SetString(long self, String key, String value);
    protected abstract boolean N_SetBinary(long self, String key, long value);
    protected abstract boolean N_SetDictionary(long self, String key, long value);
    protected abstract boolean N_SetList(long self, String key, long value);
}
