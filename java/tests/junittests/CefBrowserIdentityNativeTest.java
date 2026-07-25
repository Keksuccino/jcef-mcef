// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.junit.jupiter.api.Test;

import java.awt.GridLayout;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@NativeCefTest
class CefBrowserIdentityNativeTest {
    private static final long FUTURE_TIMEOUT_SECONDS = 10;

    private record IdentitySnapshot(boolean firstValid, boolean secondValid, boolean firstSameSelf, boolean secondSameSelf, boolean firstSameSecond, boolean secondSameFirst, boolean firstWindowless, boolean secondWindowless) {}

    private record ClosingCallbackSnapshot(boolean valid, boolean sameSelf, boolean sameOther, boolean windowless) {}

    private record ClosedSnapshot(boolean valid, boolean sameSelf, boolean sameOther, boolean windowless) {}

    @Test
    @WindowedCefTest
    void windowedBrowsersExposeIdentityAndRenderingModeAcrossLifecycleThreads() throws Exception {
        assertIdentityLifecycle(false);
    }

    @Test
    void offscreenBrowsersExposeIdentityAndRenderingModeAcrossLifecycleThreads() throws Exception {
        assertIdentityLifecycle(true);
    }

    private static void assertIdentityLifecycle(boolean offscreen) throws Exception {
        CompletableFuture<CefBrowser> firstCreated = new CompletableFuture<CefBrowser>();
        CompletableFuture<CefBrowser> secondCreated = new CompletableFuture<CefBrowser>();
        CompletableFuture<IdentitySnapshot> callbackSnapshot = new CompletableFuture<IdentitySnapshot>();
        CompletableFuture<ClosingCallbackSnapshot> firstBeforeClose = new CompletableFuture<ClosingCallbackSnapshot>();
        CompletableFuture<ClosingCallbackSnapshot> secondBeforeClose = new CompletableFuture<ClosingCallbackSnapshot>();
        Supplier<TestFrame> frameFactory = () -> new TestFrame() {
            private CefBrowser second_;

            @Override
            protected void setupTest() {
                if (offscreen) {
                    browser_ = new CefBrowserOsr(client_, "about:blank", false, null);
                    second_ = new CefBrowserOsr(client_, "about:blank", false, null);
                    browser_.createImmediately();
                    second_.createImmediately();
                } else {
                    browser_ = client_.createBrowser("about:blank", false, false, null);
                    second_ = client_.createBrowser("about:blank", false, false, null);
                    getContentPane().setLayout(new GridLayout(1, 2));
                    getContentPane().add(browser_.getUIComponent());
                    getContentPane().add(second_.getUIComponent());
                    pack();
                    setSize(900, 600);
                    setVisible(true);
                }
                super.setupTest();
            }

            @Override
            public void onAfterCreated(CefBrowser browser) {
                super.onAfterCreated(browser);
                if (browser == browser_) firstCreated.complete(browser);
                if (browser == second_) secondCreated.complete(browser);
                if (!firstCreated.isDone() || !secondCreated.isDone() || callbackSnapshot.isDone()) return;
                try {
                    callbackSnapshot.complete(captureIdentity(browser_, second_));
                } catch (Throwable failure) {
                    callbackSnapshot.completeExceptionally(failure);
                }
            }

            @Override
            public void onBeforeClose(CefBrowser browser) {
                CefBrowser other = browser == browser_ ? second_ : browser_;
                CompletableFuture<ClosingCallbackSnapshot> result = browser == browser_ ? firstBeforeClose : secondBeforeClose;
                try {
                    result.complete(captureClosingCallback(browser, other));
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                } finally {
                    // Closing the secondary browser is part of the test, not frame termination.
                    // Delay TestFrame's client cleanup until the primary browser closes.
                    if (browser == browser_) super.onBeforeClose(browser);
                }
            }
        };
        TestFrame frame = TestFrame.createOnEventDispatchThread(frameFactory);

        CefBrowser first = null;
        CefBrowser second = null;
        CountDownLatch closeRaceWorkersReady = new CountDownLatch(2);
        CountDownLatch closeRaceMayRun = new CountDownLatch(1);
        CountDownLatch closeRaceStopRequested = new CountDownLatch(1);
        CompletableFuture<Void> forwardFirstIterationCompleted = new CompletableFuture<Void>();
        CompletableFuture<Void> reverseFirstIterationCompleted = new CompletableFuture<Void>();
        CompletableFuture<Void> forwardCloseRaceFinished = new CompletableFuture<Void>();
        CompletableFuture<Void> reverseCloseRaceFinished = new CompletableFuture<Void>();
        Thread forwardCloseRaceThread = null;
        Thread reverseCloseRaceThread = null;
        try {
            first = await(firstCreated);
            second = await(secondCreated);
            assertLiveSnapshot(await(callbackSnapshot), offscreen);
            assertLiveSnapshot(captureIdentity(first, second), offscreen);

            CefBrowser compatibilityBrowser = compatibilityBrowser();
            assertFalse(first.isSame(compatibilityBrowser));
            assertFalse(second.isSame(compatibilityBrowser));

            CefBrowser raceFirst = first;
            CefBrowser raceSecond = second;
            forwardCloseRaceThread = new Thread(() -> runCloseQueryRace(raceFirst, raceSecond, secondBeforeClose, closeRaceWorkersReady, closeRaceMayRun, closeRaceStopRequested, forwardFirstIterationCompleted, forwardCloseRaceFinished), "jcef-browser-identity-close-race-first-to-second");
            reverseCloseRaceThread = new Thread(() -> runCloseQueryRace(raceSecond, raceFirst, secondBeforeClose, closeRaceWorkersReady, closeRaceMayRun, closeRaceStopRequested, reverseFirstIterationCompleted, reverseCloseRaceFinished), "jcef-browser-identity-close-race-second-to-first");
            forwardCloseRaceThread.setDaemon(true);
            reverseCloseRaceThread.setDaemon(true);
            forwardCloseRaceThread.start();
            reverseCloseRaceThread.start();
            await(closeRaceWorkersReady);
            closeRaceMayRun.countDown();
            // Each worker begins with the opposing isSame direction. Requiring one full iteration
            // from both prevents an early OnBeforeClose callback from reducing this to a zero-query race.
            await(CompletableFuture.allOf(forwardFirstIterationCompleted, reverseFirstIterationCompleted));
            second.setCloseAllowed();
            second.close(true);

            assertClosingCallbackSnapshot(await(secondBeforeClose), offscreen);
            await(CompletableFuture.allOf(forwardCloseRaceFinished, reverseCloseRaceFinished));
            awaitClosed(second, first);
        } finally {
            closeRaceStopRequested.countDown();
            closeRaceMayRun.countDown();
            try {
                joinCloseRaceThreads(forwardCloseRaceThread, reverseCloseRaceThread);
            } finally {
                frame.terminateTest();
                frame.awaitCompletion();
            }
        }

        assertClosingCallbackSnapshot(await(firstBeforeClose), offscreen);
        awaitClosed(first, second);
    }

    private static void runCloseQueryRace(CefBrowser receiver, CefBrowser operand, CompletableFuture<ClosingCallbackSnapshot> secondBeforeClose, CountDownLatch workersReady, CountDownLatch mayRun, CountDownLatch stopRequested, CompletableFuture<Void> firstIterationCompleted, CompletableFuture<Void> finished) {
        try {
            workersReady.countDown();
            await(mayRun);
            if (stopRequested.getCount() == 0) {
                finished.complete(null);
                return;
            }

            runCloseQueryIteration(receiver, operand);
            firstIterationCompleted.complete(null);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FUTURE_TIMEOUT_SECONDS);
            while (!secondBeforeClose.isDone() && stopRequested.getCount() != 0) {
                if (System.nanoTime() >= deadline) throw new AssertionError("Timed out waiting for close during browser identity query race");
                runCloseQueryIteration(receiver, operand);
            }
            finished.complete(null);
        } catch (Throwable failure) {
            firstIterationCompleted.completeExceptionally(failure);
            finished.completeExceptionally(failure);
        }
    }

    private static void runCloseQueryIteration(CefBrowser receiver, CefBrowser operand) {
        receiver.isSame(operand);
        receiver.isValid();
        receiver.isSame(receiver);
        receiver.isWindowRenderingDisabled();
    }

    private static void joinCloseRaceThreads(Thread forwardThread, Thread reverseThread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FUTURE_TIMEOUT_SECONDS);
        joinCloseRaceThread(forwardThread, deadline);
        joinCloseRaceThread(reverseThread, deadline);
        boolean forwardAlive = forwardThread != null && forwardThread.isAlive();
        boolean reverseAlive = reverseThread != null && reverseThread.isAlive();
        if (!forwardAlive && !reverseAlive) return;
        if (forwardAlive) forwardThread.interrupt();
        if (reverseAlive) reverseThread.interrupt();
        throw new AssertionError("Identity close/query race threads did not terminate: forward=" + forwardAlive + ", reverse=" + reverseAlive);
    }

    private static void joinCloseRaceThread(Thread thread, long deadline) throws InterruptedException {
        if (thread == null) return;
        while (thread.isAlive()) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) return;
            long waitMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
            int waitNanos = (int) (remainingNanos - TimeUnit.MILLISECONDS.toNanos(waitMillis));
            thread.join(waitMillis, waitNanos);
        }
    }

    private static IdentitySnapshot captureIdentity(CefBrowser first, CefBrowser second) {
        return new IdentitySnapshot(first.isValid(), second.isValid(), first.isSame(first), second.isSame(second), first.isSame(second), second.isSame(first), first.isWindowRenderingDisabled(), second.isWindowRenderingDisabled());
    }

    private static ClosingCallbackSnapshot captureClosingCallback(CefBrowser browser, CefBrowser other) {
        return new ClosingCallbackSnapshot(browser.isValid(), browser.isSame(browser), browser.isSame(other), browser.isWindowRenderingDisabled());
    }

    private static ClosedSnapshot captureClosed(CefBrowser browser, CefBrowser other) {
        return new ClosedSnapshot(browser.isValid(), browser.isSame(browser), browser.isSame(other), browser.isWindowRenderingDisabled());
    }

    private static void assertLiveSnapshot(IdentitySnapshot snapshot, boolean offscreen) {
        assertTrue(snapshot.firstValid());
        assertTrue(snapshot.secondValid());
        assertTrue(snapshot.firstSameSelf());
        assertTrue(snapshot.secondSameSelf());
        assertFalse(snapshot.firstSameSecond());
        assertFalse(snapshot.secondSameFirst());
        assertEquals(offscreen, snapshot.firstWindowless());
        assertEquals(offscreen, snapshot.secondWindowless());
    }

    private static void assertClosingCallbackSnapshot(ClosingCallbackSnapshot snapshot, boolean offscreen) {
        assertTrue(snapshot.valid());
        assertTrue(snapshot.sameSelf());
        assertFalse(snapshot.sameOther());
        assertEquals(offscreen, snapshot.windowless());
    }

    private static void assertClosedSnapshot(ClosedSnapshot snapshot) {
        assertFalse(snapshot.valid());
        assertFalse(snapshot.sameSelf());
        assertFalse(snapshot.sameOther());
        assertFalse(snapshot.windowless());
    }

    private static void awaitClosed(CefBrowser browser, CefBrowser other) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FUTURE_TIMEOUT_SECONDS);
        while (browser.isValid()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Timed out waiting for browser identity teardown");
            Thread.onSpinWait();
        }
        assertClosedSnapshot(captureClosed(browser, other));
    }

    private static CefBrowser compatibilityBrowser() {
        InvocationHandler handler = (proxy, method, arguments) -> {
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, arguments);
            throw new UnsupportedOperationException(method.getName());
        };
        return (CefBrowser) Proxy.newProxyInstance(CefBrowser.class.getClassLoader(), new Class<?>[] {CefBrowser.class}, handler);
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for identity lifecycle coordination");
    }
}
