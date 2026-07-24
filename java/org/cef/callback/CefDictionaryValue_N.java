// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.callback;

final class CefDictionaryValue_N extends CefDictionaryValue {
    CefDictionaryValue_N() {}

    static CefDictionaryValue createNative() {
        return N_Create();
    }

    private static native CefDictionaryValue_N N_Create();
    @Override
    protected native void N_Dispose(long self);
    @Override
    protected native boolean N_IsValid(long self);
    @Override
    protected native boolean N_IsOwned(long self);
    @Override
    protected native boolean N_IsReadOnly(long self);
    @Override
    protected native boolean N_IsSame(long self, long that);
    @Override
    protected native boolean N_IsEqual(long self, long that);
    @Override
    protected native CefDictionaryValue_N N_Copy(long self, boolean excludeEmptyChildren);
    @Override
    protected native long N_GetSize(long self);
    @Override
    protected native boolean N_Clear(long self);
    @Override
    protected native boolean N_HasKey(long self, String key);
    @Override
    protected native String[] N_GetKeys(long self);
    @Override
    protected native boolean N_Remove(long self, String key);
    @Override
    protected native int N_GetType(long self, String key);
    @Override
    protected native CefValue_N N_GetValue(long self, String key);
    @Override
    protected native boolean N_GetBool(long self, String key);
    @Override
    protected native int N_GetInt(long self, String key);
    @Override
    protected native double N_GetDouble(long self, String key);
    @Override
    protected native String N_GetString(long self, String key);
    @Override
    protected native CefBinaryValue_N N_GetBinary(long self, String key);
    @Override
    protected native CefDictionaryValue_N N_GetDictionary(long self, String key);
    @Override
    protected native CefListValue_N N_GetList(long self, String key);
    @Override
    protected native boolean N_SetValue(long self, String key, long value);
    @Override
    protected native boolean N_SetNull(long self, String key);
    @Override
    protected native boolean N_SetBool(long self, String key, boolean value);
    @Override
    protected native boolean N_SetInt(long self, String key, int value);
    @Override
    protected native boolean N_SetDouble(long self, String key, double value);
    @Override
    protected native boolean N_SetString(long self, String key, String value);
    @Override
    protected native boolean N_SetBinary(long self, String key, long value);
    @Override
    protected native boolean N_SetDictionary(long self, String key, long value);
    @Override
    protected native boolean N_SetList(long self, String key, long value);
}
