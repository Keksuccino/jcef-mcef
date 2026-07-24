// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.SwingUtilities;

class TestFrameLifecycleTest {
    @Test
    void terminationIsQueuedPastTheCurrentEdtTurnAndOnlyRunsOnce() throws Exception {
        TestFrameLifecycle lifecycle = new TestFrameLifecycle();
        AtomicBoolean requestReturned = new AtomicBoolean();
        AtomicBoolean observedReturn = new AtomicBoolean();
        AtomicInteger terminationCalls = new AtomicInteger();
        CountDownLatch terminationRan = new CountDownLatch(1);

        Runnable termination = () -> {
            observedReturn.set(requestReturned.get());
            terminationCalls.incrementAndGet();
            terminationRan.countDown();
        };
        Runnable requestTermination = () -> {
            lifecycle.enqueueTermination(termination);
            lifecycle.enqueueTermination(() -> terminationCalls.addAndGet(100));

            assertEquals(0, terminationCalls.get());
            requestReturned.set(true);
        };
        SwingUtilities.invokeAndWait(requestTermination);

        assertTrue(terminationRan.await(5, TimeUnit.SECONDS));
        assertTrue(observedReturn.get());
        assertEquals(1, terminationCalls.get());
    }

    @Test
    void cleanupAndCompletionAreQueuedPastTheCurrentCallbackTurn() throws Exception {
        TestFrameLifecycle lifecycle = new TestFrameLifecycle();
        AtomicBoolean callbackReturned = new AtomicBoolean();
        AtomicBoolean cleanupObservedReturn = new AtomicBoolean();
        AtomicInteger cleanupCalls = new AtomicInteger();

        Runnable cleanup = () -> {
            cleanupObservedReturn.set(callbackReturned.get());
            cleanupCalls.incrementAndGet();
        };
        Runnable callback = () -> {
            lifecycle.enqueueCleanup(cleanup);
            lifecycle.enqueueCleanup(() -> cleanupCalls.addAndGet(100));

            assertFalse(awaitWithoutBlocking(lifecycle));
            assertEquals(0, cleanupCalls.get());
            callbackReturned.set(true);
        };
        SwingUtilities.invokeAndWait(callback);

        assertTrue(lifecycle.awaitCompletion(5, TimeUnit.SECONDS));
        assertTrue(cleanupObservedReturn.get());
        assertEquals(1, cleanupCalls.get());
        lifecycle.rethrowCleanupFailure();
    }

    @Test
    void cleanupFailureCompletesTheWaitAndIsRethrown() throws Exception {
        TestFrameLifecycle lifecycle = new TestFrameLifecycle();
        IllegalStateException expected = new IllegalStateException("expected cleanup failure");

        lifecycle.enqueueCleanup(() -> { throw expected; });

        assertTrue(lifecycle.awaitCompletion(5, TimeUnit.SECONDS));
        assertSame(expected, assertThrows(IllegalStateException.class, lifecycle::rethrowCleanupFailure));
    }

    private static boolean awaitWithoutBlocking(TestFrameLifecycle lifecycle) {
        try {
            return lifecycle.awaitCompletion(0, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted during a non-blocking lifecycle check", exception);
        }
    }
}
