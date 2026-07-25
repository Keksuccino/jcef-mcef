// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.callback;

/** Shared one-shot admission for native permission callbacks. */
abstract class CefOneShotNativeCallback extends CefNativeAdapter {
    private boolean pending_ = true;
    private long nativeRef_;

    @Override
    public final synchronized void setNativeRef(String identifier, long nativeRef) {
        nativeRef_ = nativeRef;
    }

    @Override
    public final synchronized long getNativeRef(String identifier) {
        return nativeRef_;
    }

    /**
     * Claims the native proxy exactly once. Callers hold this object's monitor across both this
     * method and the native invocation; native completion reentrantly clears the stored handle
     * before that monitor is released.
     */
    protected final long claimNativeRef() {
        if (!pending_) return 0;
        pending_ = false;
        return nativeRef_;
    }

    /** Atomically detaches the native reference for the single shared native release path. */
    final synchronized long takeNativeRef() {
        pending_ = false;
        long nativeRef = nativeRef_;
        nativeRef_ = 0;
        return nativeRef;
    }
}
