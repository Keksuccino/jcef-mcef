// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.callback;

/**
 * Callback used for asynchronous continuation of a media-access permission request. Each instance
 * is one-shot: only the first completion is honored. Later calls, and calls after the native
 * request has been invalidated by browser or runtime teardown, do nothing.
 */
public interface CefMediaAccessCallback {
    /**
     * Allows the specified raw permission mask. For a {@code getUserMedia} request the device
     * audio/video bits in the requested mask indicate that {@code allowedPermissions} must equal
     * the complete {@code requestedPermissions} mask passed to the handler.
     */
    void Continue(int allowedPermissions);

    /** Cancels the media-access request if this callback has not already been completed. */
    void Cancel();
}
