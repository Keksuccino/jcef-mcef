// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.handler;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefMediaAccessCallback;
import org.cef.callback.CefPermissionPromptCallback;

/** Convenience adapter with safe CEF-default behavior. */
public abstract class CefPermissionHandlerAdapter implements CefPermissionHandler {
    @Override
    public boolean onRequestMediaAccessPermission(CefBrowser browser, CefFrame frame, String requestingOrigin, int requestedPermissions, CefMediaAccessCallback callback) {
        return false;
    }

    @Override
    public boolean onShowPermissionPrompt(CefBrowser browser, long promptId, String requestingOrigin, int requestedPermissions, CefPermissionPromptCallback callback) {
        return false;
    }

    @Override
    public void onDismissPermissionPrompt(CefBrowser browser, long promptId, int result) {}
}
