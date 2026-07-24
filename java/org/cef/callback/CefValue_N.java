// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.callback;

final class CefValue_N extends CefValue {
    CefValue_N() {}

    static CefValue createNative() {
        return N_Create();
    }

    private static native CefValue_N N_Create();
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
    protected native CefValue_N N_Copy(long self);
    @Override
    protected native int N_GetType(long self);
    @Override
    protected native boolean N_GetBool(long self);
    @Override
    protected native int N_GetInt(long self);
    @Override
    protected native double N_GetDouble(long self);
    @Override
    protected native String N_GetString(long self);
    @Override
    protected native CefBinaryValue_N N_GetBinary(long self);
    @Override
    protected native CefDictionaryValue_N N_GetDictionary(long self);
    @Override
    protected native CefListValue_N N_GetList(long self);
    @Override
    protected native boolean N_SetNull(long self);
    @Override
    protected native boolean N_SetBool(long self, boolean value);
    @Override
    protected native boolean N_SetInt(long self, int value);
    @Override
    protected native boolean N_SetDouble(long self, double value);
    @Override
    protected native boolean N_SetString(long self, String value);
    @Override
    protected native boolean N_SetBinary(long self, long value);
    @Override
    protected native boolean N_SetDictionary(long self, long value);
    @Override
    protected native boolean N_SetList(long self, long value);
}
