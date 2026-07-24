// Copyright (c) 2024 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

import org.cef.callback.CefNative;

class CefRegistration_N extends CefRegistration implements CefNative {
    // Used internally to store a pointer to the CEF object.
    private volatile long N_CefHandle = 0;

    @Override
    public void setNativeRef(String identifier, long nativeRef) {
        N_CefHandle = nativeRef;
    }

    @Override
    public long getNativeRef(String identifier) {
        return N_CefHandle;
    }

    @Override
    public synchronized void dispose() {
        long nativeHandle = N_CefHandle;
        if (nativeHandle == 0) return;
        try {
            N_Dispose(nativeHandle);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
            N_CefHandle = 0;
        }
    }

    private final native void N_Dispose(long self);
}
