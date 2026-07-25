// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.callback;

class CefMediaAccessCallback_N extends CefOneShotNativeCallback implements CefMediaAccessCallback {
    CefMediaAccessCallback_N() {}

    @Override
    protected void finalize() throws Throwable {
        try {
            Cancel();
        } finally {
            super.finalize();
        }
    }

    @Override
    public synchronized void Continue(int allowedPermissions) {
        long self = claimNativeRef();
        if (self == 0) return;
        try {
            N_Continue(self, allowedPermissions);
        } catch (UnsatisfiedLinkError error) {
            error.printStackTrace();
        }
    }

    @Override
    public synchronized void Cancel() {
        long self = claimNativeRef();
        if (self == 0) return;
        try {
            N_Cancel(self);
        } catch (UnsatisfiedLinkError error) {
            error.printStackTrace();
        }
    }

    private native void N_Continue(long self, int allowedPermissions);
    private native void N_Cancel(long self);
}
