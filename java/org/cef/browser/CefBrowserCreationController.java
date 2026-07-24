// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

/**
 * Serializes the asynchronous transition from a Java browser object to its native CEF browser.
 */
final class CefBrowserCreationController {
    private enum State { NEW, PENDING, CREATED }

    private State state_ = State.NEW;

    synchronized boolean begin(boolean nativeBrowserExists, boolean lifecycleEnding) {
        if (nativeBrowserExists || lifecycleEnding || state_ != State.NEW) return false;
        state_ = State.PENDING;
        return true;
    }

    synchronized void succeeded() {
        if (state_ == State.PENDING) state_ = State.CREATED;
    }

    synchronized void failed() {
        if (state_ == State.PENDING) state_ = State.NEW;
    }

    synchronized boolean isCreated() {
        return state_ == State.CREATED;
    }

    synchronized boolean isNew() {
        return state_ == State.NEW;
    }

    synchronized boolean isPending() {
        return state_ == State.PENDING;
    }
}
