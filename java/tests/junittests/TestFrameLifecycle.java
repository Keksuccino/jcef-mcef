// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

/** Serializes the terminal operations of a {@link TestFrame} onto later EDT turns. */
final class TestFrameLifecycle {
    private final AtomicBoolean terminationQueued_ = new AtomicBoolean();
    private final AtomicBoolean cleanupQueued_ = new AtomicBoolean();
    private final AtomicReference<Throwable> cleanupFailure_ = new AtomicReference<Throwable>();
    private final CountDownLatch completion_ = new CountDownLatch(1);

    void enqueueTermination(Runnable termination) {
        if (!terminationQueued_.compareAndSet(false, true)) return;

        try {
            SwingUtilities.invokeLater(termination);
        } catch (RuntimeException | Error failure) {
            // A failed enqueue did not initiate termination, so permit a later caller to retry.
            terminationQueued_.set(false);
            throw failure;
        }
    }

    void enqueueCleanup(Runnable cleanup) {
        if (!cleanupQueued_.compareAndSet(false, true)) return;

        try {
            // CEF is still unwinding OnBeforeClose when this method is called. Disposing the client
            // inline can release native handler references that the callback dispatch still owns.
            SwingUtilities.invokeLater(() -> runCleanup(cleanup));
        } catch (RuntimeException | Error failure) {
            cleanupFailure_.compareAndSet(null, failure);
            completion_.countDown();
        }
    }

    private void runCleanup(Runnable cleanup) {
        try {
            cleanup.run();
        } catch (Throwable failure) {
            cleanupFailure_.compareAndSet(null, failure);
        } finally {
            completion_.countDown();
        }
    }

    boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
        return completion_.await(timeout, unit);
    }

    void rethrowCleanupFailure() {
        Throwable failure = cleanupFailure_.get();
        if (failure instanceof RuntimeException) throw(RuntimeException) failure;
        if (failure instanceof Error) throw(Error) failure;
        if (failure != null) throw new AssertionError("CEF integration test cleanup failed", failure);
    }
}
