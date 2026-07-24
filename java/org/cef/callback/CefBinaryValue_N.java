// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.callback;

final class CefBinaryValue_N extends CefBinaryValue {
    CefBinaryValue_N() {}

    static CefBinaryValue createNative(byte[] data, int offset, int length) {
        return N_Create(data, offset, length);
    }

    private static native CefBinaryValue_N N_Create(byte[] data, int offset, int length);
    @Override
    protected native void N_Dispose(long self);
    @Override
    protected native boolean N_IsValid(long self);
    @Override
    protected native boolean N_IsOwned(long self);
    @Override
    protected native boolean N_IsSame(long self, long that);
    @Override
    protected native boolean N_IsEqual(long self, long that);
    @Override
    protected native CefBinaryValue_N N_Copy(long self);
    @Override
    protected native long N_GetSize(long self);
    @Override
    protected native int N_GetData(long self, byte[] buffer, int bufferOffset, int length, long dataOffset);
}
