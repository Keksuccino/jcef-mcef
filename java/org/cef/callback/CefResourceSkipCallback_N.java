// Copyright (c) 2024 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.callback;

class CefResourceSkipCallback_N extends CefNativeAdapter implements CefResourceSkipCallback {
    private long maxBytesToSkip_;
    private boolean pending_;

    CefResourceSkipCallback_N() {}

    @Override
    protected void finalize() throws Throwable {
        try {
            // CEF documents zero as the asynchronous skip failure signal. Continue() atomically
            // claims the pending native callback, so explicit completion and finalization cannot
            // consume it twice.
            Continue(0);
        } finally {
            super.finalize();
        }
    }

    @Override
    public void Continue(long bytes_skipped) {
        long self;
        long maxBytesToSkip;
        synchronized (this) {
            if (!pending_) return;
            self = getNativeRef(null);
            maxBytesToSkip = maxBytesToSkip_;
            pending_ = false;
            maxBytesToSkip_ = 0;
        }
        if (self == 0) return;
        long safeBytesSkipped = normalizeBytesSkipped(bytes_skipped, maxBytesToSkip);
        try {
            N_Continue(self, safeBytesSkipped, maxBytesToSkip);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    public synchronized void setBytesToSkip(long bytesToSkip) {
        maxBytesToSkip_ = bytesToSkip;
        pending_ = true;
    }

    public synchronized void clearPending() {
        maxBytesToSkip_ = 0;
        pending_ = false;
    }

    private static long normalizeBytesSkipped(long bytesSkipped, long maximum) {
        return bytesSkipped > maximum ? 0 : bytesSkipped;
    }

    private final native void N_Continue(long self, long bytes_skipped, long maxBytesToSkip);
}
