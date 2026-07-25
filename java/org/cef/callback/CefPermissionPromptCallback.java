// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.callback;

import org.cef.handler.CefPermissionRequestResult;

/**
 * Callback used for asynchronous continuation of a general permission prompt. Each instance is
 * one-shot: only the first valid completion is honored. Later calls with valid results, and valid
 * calls after prompt dismissal or native teardown, do nothing. Invalid results always throw and
 * never consume the callback.
 */
public interface CefPermissionPromptCallback {
    /**
     * Completes the prompt with a value from {@link CefPermissionRequestResult}.
     *
     * @throws IllegalArgumentException if {@code result} is not a CEF 151 completion result. An
     *      invalid attempt does not consume this callback.
     */
    void Continue(int result);
}
