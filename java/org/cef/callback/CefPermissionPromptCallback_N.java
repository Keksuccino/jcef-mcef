// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.callback;

import org.cef.handler.CefPermissionRequestResult;

class CefPermissionPromptCallback_N
        extends CefOneShotNativeCallback implements CefPermissionPromptCallback {
    CefPermissionPromptCallback_N() {}

    @Override
    protected void finalize() throws Throwable {
        try {
            Continue(CefPermissionRequestResult.CEF_PERMISSION_RESULT_IGNORE);
        } finally {
            super.finalize();
        }
    }

    @Override
    public synchronized void Continue(int result) {
        if (!CefPermissionRequestResult.isValid(result))
            throw new IllegalArgumentException("Invalid CEF permission result: " + result);
        long self = claimNativeRef();
        if (self == 0) return;
        try {
            N_Continue(self, result);
        } catch (UnsatisfiedLinkError error) {
            error.printStackTrace();
        }
    }

    private native void N_Continue(long self, int result);
}
