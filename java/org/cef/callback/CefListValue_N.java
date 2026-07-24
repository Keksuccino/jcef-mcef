// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.callback;

final class CefListValue_N extends CefListValue {
    CefListValue_N() {}

    static CefListValue createNative() {
        return N_Create();
    }

    private static native CefListValue_N N_Create();
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
    protected native CefListValue_N N_Copy(long self);
    @Override
    protected native boolean N_SetSize(long self, long size);
    @Override
    protected native long N_GetSize(long self);
    @Override
    protected native boolean N_Clear(long self);
    @Override
    protected native boolean N_Remove(long self, long index);
    @Override
    protected native int N_GetType(long self, long index);
    @Override
    protected native CefValue_N N_GetValue(long self, long index);
    @Override
    protected native boolean N_GetBool(long self, long index);
    @Override
    protected native int N_GetInt(long self, long index);
    @Override
    protected native double N_GetDouble(long self, long index);
    @Override
    protected native String N_GetString(long self, long index);
    @Override
    protected native CefBinaryValue_N N_GetBinary(long self, long index);
    @Override
    protected native CefDictionaryValue_N N_GetDictionary(long self, long index);
    @Override
    protected native CefListValue_N N_GetList(long self, long index);
    @Override
    protected native boolean N_SetValue(long self, long index, long value);
    @Override
    protected native boolean N_SetNull(long self, long index);
    @Override
    protected native boolean N_SetBool(long self, long index, boolean value);
    @Override
    protected native boolean N_SetInt(long self, long index, int value);
    @Override
    protected native boolean N_SetDouble(long self, long index, double value);
    @Override
    protected native boolean N_SetString(long self, long index, String value);
    @Override
    protected native boolean N_SetBinary(long self, long index, long value);
    @Override
    protected native boolean N_SetDictionary(long self, long index, long value);
    @Override
    protected native boolean N_SetList(long self, long index, long value);
}
