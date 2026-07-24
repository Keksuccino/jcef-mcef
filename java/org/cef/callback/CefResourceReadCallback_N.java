// Copyright (c) 2024 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.callback;

class CefResourceReadCallback_N extends CefNativeAdapter implements CefResourceReadCallback {
    // The native buffer where to copy the data to.
    private long N_NativeBufferRef;

    // The Java buffer which the application is expected to fill with data.
    private byte[] N_JavaBuffer;
    private boolean pending_;

    CefResourceReadCallback_N() {}

    @Override
    protected void finalize() throws Throwable {
        try {
            // A dropped asynchronous callback must release its native callback reference and
            // unblock the request. Continue() atomically claims the pending data_out pointer.
            Continue(-2);
        } finally {
            super.finalize();
        }
    }

    public synchronized void setBufferRefs(long nativeBufferRef, byte[] javaBuffer) {
        N_NativeBufferRef = nativeBufferRef;
        N_JavaBuffer = javaBuffer;
        pending_ = true;
    }

    @Override
    public synchronized byte[] getBuffer() {
        return N_JavaBuffer;
    }

    @Override
    public void Continue(int bytes_read) {
        long nativeBufferRef;
        byte[] javaBuffer;
        long self;
        synchronized (this) {
            if (!pending_) return;
            nativeBufferRef = N_NativeBufferRef;
            javaBuffer = N_JavaBuffer;
            self = getNativeRef(null);
            pending_ = false;
            N_NativeBufferRef = 0;
            N_JavaBuffer = null;
        }
        if (self == 0) return;
        int safeBytesRead =
                javaBuffer == null ? -2 : normalizeBytesRead(bytes_read, javaBuffer.length);
        try {
            N_Continue(self, safeBytesRead, nativeBufferRef, javaBuffer);
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
    }

    public synchronized void clearBufferRefs() {
        pending_ = false;
        N_NativeBufferRef = 0;
        N_JavaBuffer = null;
    }

    private static int normalizeBytesRead(int bytesRead, int capacity) {
        return bytesRead > capacity ? -2 : bytesRead;
    }

    static boolean testSetupCallForTesting(Object target) {
        return N_TestSetupCallForTesting(target);
    }

    static boolean testOpenCallbackRetentionForTesting(boolean callSucceeded, boolean result, boolean handleRequest) {
        return N_TestOpenCallbackRetentionForTesting(callSucceeded, result, handleRequest);
    }

    private final native void N_Continue(long self, int bytes_read, long nativeBufferRef, byte[] javaBuffer);
    private static native boolean N_TestSetupCallForTesting(Object target);
    private static native boolean N_TestOpenCallbackRetentionForTesting(boolean callSucceeded, boolean result, boolean handleRequest);
}
