// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.callback;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Shared lifecycle implementation for the ref-counted CEF value wrappers. */
abstract class CefNativeValue implements CefNative, AutoCloseable {
    @FunctionalInterface
    protected interface NativeBooleanCall {
        boolean invoke(long self);
    }

    @FunctionalInterface
    protected interface NativeIntCall {
        int invoke(long self);
    }

    @FunctionalInterface
    protected interface NativeLongCall {
        long invoke(long self);
    }

    @FunctionalInterface
    protected interface NativeDoubleCall {
        double invoke(long self);
    }

    @FunctionalInterface
    protected interface NativeObjectCall<T> {
        T invoke(long self);
    }

    @FunctionalInterface
    protected interface NativeVoidCall {
        void invoke(long self);
    }

    // All value calls take the shared read lock while disposal takes the write lock. This keeps a
    // raw JNI handle alive for the complete native call without serializing independent value
    // operations; peer handles used by isSame(), isEqual(), and Set*() are protected as well.
    private static final ReentrantReadWriteLock LIFECYCLE_LOCK = new ReentrantReadWriteLock();
    private static final Lock READ_LOCK = LIFECYCLE_LOCK.readLock();
    private static final Lock WRITE_LOCK = LIFECYCLE_LOCK.writeLock();

    private final String typeName_;
    private volatile long nativeRef_;

    protected CefNativeValue(String typeName) {
        typeName_ = typeName;
    }

    @Override
    public final void setNativeRef(String identifier, long nativeRef) {
        // A getter/copy call can create a new peer wrapper while this thread holds the shared read
        // lock for the source wrapper. The peer is not published until JNI returns, so initializing
        // its zero handle here is safe and avoids an impossible read-to-write lock upgrade.
        if (LIFECYCLE_LOCK.getReadHoldCount() != 0
                && !LIFECYCLE_LOCK.isWriteLockedByCurrentThread()) {
            if (nativeRef_ != 0)
                throw new IllegalStateException("Cannot replace a CEF value handle during a native call");
            nativeRef_ = nativeRef;
            return;
        }
        WRITE_LOCK.lock();
        try {
            nativeRef_ = nativeRef;
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    @Override
    public final long getNativeRef(String identifier) {
        return nativeRef_;
    }

    protected final boolean invokeBoolean(NativeBooleanCall call) {
        READ_LOCK.lock();
        try {
            return call.invoke(requireNativeRefLocked());
        } finally {
            READ_LOCK.unlock();
        }
    }

    protected final boolean invokeBooleanIfPresent(NativeBooleanCall call, boolean fallback) {
        READ_LOCK.lock();
        try {
            return nativeRef_ == 0 ? fallback : call.invoke(nativeRef_);
        } finally {
            READ_LOCK.unlock();
        }
    }

    protected final int invokeInt(NativeIntCall call) {
        READ_LOCK.lock();
        try {
            return call.invoke(requireNativeRefLocked());
        } finally {
            READ_LOCK.unlock();
        }
    }

    protected final long invokeLong(NativeLongCall call) {
        READ_LOCK.lock();
        try {
            return call.invoke(requireNativeRefLocked());
        } finally {
            READ_LOCK.unlock();
        }
    }

    protected final double invokeDouble(NativeDoubleCall call) {
        READ_LOCK.lock();
        try {
            return call.invoke(requireNativeRefLocked());
        } finally {
            READ_LOCK.unlock();
        }
    }

    protected final <T> T invokeObject(NativeObjectCall<T> call) {
        READ_LOCK.lock();
        try {
            return call.invoke(requireNativeRefLocked());
        } finally {
            READ_LOCK.unlock();
        }
    }

    protected final void disposeNative(NativeVoidCall call) {
        WRITE_LOCK.lock();
        try {
            if (nativeRef_ == 0) return;
            call.invoke(nativeRef_);
            if (nativeRef_ != 0) {
                throw new IllegalStateException(typeName_ + " native disposal did not clear its handle");
            }
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /**
     * Returns a peer handle while the caller holds the shared lifecycle read lock. Keeping the
     * lookup inside an invoke* callback is essential; returning this raw handle to general Java
     * code would allow disposal to race the native AddRef operation.
     */
    protected final long peerNativeRef(CefNativeValue peer) {
        if (LIFECYCLE_LOCK.getReadHoldCount() == 0
                && !LIFECYCLE_LOCK.isWriteLockedByCurrentThread()) {
            throw new IllegalStateException("Peer native handles may only be read during a protected native call");
        }
        return peer.requireNativeRefLocked();
    }

    private long requireNativeRefLocked() {
        if (nativeRef_ == 0) throw new IllegalStateException(typeName_ + " has been disposed");
        return nativeRef_;
    }

    public abstract void dispose();

    @Override
    public final void close() {
        dispose();
    }

    @SuppressWarnings("deprecation")
    @Override
    protected final void finalize() throws Throwable {
        try {
            dispose();
        } finally {
            super.finalize();
        }
    }
}
