// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.handler;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefMediaAccessCallback;
import org.cef.callback.CefPermissionPromptCallback;

/**
 * Handles permission requests on the CEF browser-process UI thread. Continuation callback methods
 * may be called from any thread and are safe to retain for asynchronous completion.
 */
public interface CefPermissionHandler {
    /**
     * Handles a media-access request. Return {@code true} only when this handler owns and will
     * complete the callback, either synchronously or later. Returning {@code false} uses CEF's
     * default behavior: Chrome style displays permission UI and Alloy style denies the request.
     * {@code requestingOrigin} is normalized origin text supplied by CEF but must still be treated
     * as untrusted input. {@code requestedPermissions} is a raw combination of
     * {@link CefMediaAccessPermissionTypes} bits. The {@code frame} wrapper identifies the
     * requesting subframe and is valid only for this invocation; do not retain it. The continuation
     * callback may be retained. This method is not called when Chromium's
     * {@code --enable-media-stream} switch grants all media permissions.
     */
    default boolean onRequestMediaAccessPermission(CefBrowser browser, CefFrame frame, String requestingOrigin, int requestedPermissions, CefMediaAccessCallback callback) {
        return false;
    }

    /**
     * Handles a general permission prompt. {@code promptId} preserves the bit pattern of CEF's
     * unsigned 64-bit identifier; use unsigned Java helpers when formatting or comparing its
     * magnitude. {@code requestingOrigin} is normalized origin text supplied by CEF but must still
     * be treated as untrusted input. {@code requestedPermissions} is a raw combination of
     * {@link CefPermissionRequestTypes} bits. Return {@code true} only when this handler owns and
     * will complete the callback, either synchronously or later. Returning {@code false} uses CEF's
     * default handling: Chrome style displays permission UI and Alloy style uses
     * {@link CefPermissionRequestResult#CEF_PERMISSION_RESULT_IGNORE}, which may leave related
     * promises unresolved.
     */
    default boolean onShowPermissionPrompt(CefBrowser browser, long promptId, String requestingOrigin, int requestedPermissions, CefPermissionPromptCallback callback) {
        return false;
    }

    /**
     * Reports dismissal of a prompt previously owned by this handler. Navigation and browser close
     * dismiss an outstanding prompt with
     * {@link CefPermissionRequestResult#CEF_PERMISSION_RESULT_IGNORE}. This method is not called
     * for a prompt whose {@link #onShowPermissionPrompt} invocation returned {@code false}.
     */
    default void onDismissPermissionPrompt(CefBrowser browser, long promptId, int result) {}
}
