// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import java.awt.EventQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Child-JVM fixture for process-completion behavior that cannot be verified in JUnit's JVM. */
public final class TestProcessExitCoordinatorProcess {
    static final String AWT_SUCCESS_MODE = "awt-success";
    static final String AWT_FAILURE_MODE = "awt-failure";
    static final String AWT_ACTION_FAILURE_MODE = "awt-action-failure";
    static final String FAILURE_MODE = "failure";
    static final String TIMEOUT_MODE = "timeout";
    static final String AWT_SHUTDOWN_MARKER = "awt-shutdown-requested";
    static final String AWT_QUIESCENCE_MARKER = "awt-threads-quiescent";
    static final String NATURAL_RETURN_MARKER = "coordinator-returned-naturally";
    static final int FAILURE_STATUS = 37;
    private static final long FIXTURE_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final long AWT_SUCCESS_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);

    private TestProcessExitCoordinatorProcess() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected one fixture mode");
        if (AWT_SUCCESS_MODE.equals(args[0])) {
            AwtContext awtContext = initializeAwt();
            finishAfterAwtShutdown(0, awtContext);
            System.out.println(NATURAL_RETURN_MARKER);
            return;
        }
        if (AWT_FAILURE_MODE.equals(args[0])) {
            AwtContext awtContext = initializeAwt();
            startAwtQuiescenceObserver(awtContext);
            finishAfterAwtShutdown(FAILURE_STATUS, awtContext);
            throw new AssertionError("AWT failure completion unexpectedly returned");
        }
        if (AWT_ACTION_FAILURE_MODE.equals(args[0])) {
            initializeAwt();
            TestProcessExitCoordinator.finish(0, FIXTURE_TIMEOUT_NANOS, TestProcessExitCoordinatorProcess::failAwtShutdown);
            throw new AssertionError("Failed completion unexpectedly returned");
        }
        if (FAILURE_MODE.equals(args[0])) {
            TestProcessExitCoordinator.finish(FAILURE_STATUS);
            throw new AssertionError("Failure completion unexpectedly returned");
        }
        if (TIMEOUT_MODE.equals(args[0])) {
            CountDownLatch started = new CountDownLatch(1);
            Thread pending = new Thread(() -> awaitForever(started), "stuck-fixture-cleanup");
            pending.start();
            if (!started.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timeout fixture thread did not start");
            TestProcessExitCoordinator.finish(0, FIXTURE_TIMEOUT_NANOS);
            throw new AssertionError("Timed-out completion unexpectedly returned");
        }
        throw new IllegalArgumentException("Unknown fixture mode: " + args[0]);
    }

    private static AwtContext initializeAwt() throws Exception {
        AtomicReference<Thread> eventDispatchThread = new AtomicReference<Thread>();
        CountDownLatch eventStarted = new CountDownLatch(1);
        CountDownLatch releaseEvent = new CountDownLatch(1);
        try {
            EventQueue.invokeLater(() -> blockAwtEvent(eventDispatchThread, eventStarted, releaseEvent));
            if (!eventStarted.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Blocking AWT event did not start");
            List<Thread> awtThreads = new ArrayList<Thread>();
            awtThreads.add(eventDispatchThread.get());
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if ("AWT-Shutdown".equals(thread.getName()) && thread.isAlive()) awtThreads.add(thread);
            }
            if (awtThreads.size() < 2) throw new IllegalStateException("AWT shutdown thread was not present after EventQueue initialization");
            return new AwtContext(List.copyOf(awtThreads), releaseEvent);
        } catch (InterruptedException failure) {
            releaseEvent.countDown();
            Thread.currentThread().interrupt();
            throw failure;
        } catch (Exception | Error failure) {
            // Never strand the non-daemon EDT when fixture setup itself fails. Releasing the event
            // preserves the original exception while allowing AWT's ordinary cleanup to finish.
            releaseEvent.countDown();
            throw failure;
        }
    }

    private static void finishAfterAwtShutdown(int status, AwtContext awtContext) {
        TestProcessExitCoordinator.ShutdownAction shutdownAction = () -> requestAwtShutdown(awtContext);
        TestProcessExitCoordinator.finish(status, AWT_SUCCESS_TIMEOUT_NANOS, shutdownAction);
    }

    private static void requestAwtShutdown(AwtContext awtContext) throws Exception {
        System.out.println(AWT_SHUTDOWN_MARKER);
        TestProcessExitCoordinator.stopAwtEventDispatchThreads();
        awtContext.releaseEvent().countDown();
    }

    private static void failAwtShutdown() {
        throw new IllegalStateException("injected AWT shutdown action failure");
    }

    private static void startAwtQuiescenceObserver(AwtContext awtContext) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Thread observer = new Thread(() -> observeAwtQuiescence(awtContext.threads(), started), "awt-quiescence-observer");
        try {
            observer.start();
            if (!started.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("AWT quiescence observer did not start");
        } catch (InterruptedException failure) {
            awtContext.releaseEvent().countDown();
            Thread.currentThread().interrupt();
            throw failure;
        } catch (Exception | Error failure) {
            awtContext.releaseEvent().countDown();
            throw failure;
        }
    }

    private static void blockAwtEvent(AtomicReference<Thread> eventDispatchThread, CountDownLatch eventStarted, CountDownLatch releaseEvent) {
        eventDispatchThread.set(Thread.currentThread());
        eventStarted.countDown();
        try {
            releaseEvent.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void observeAwtQuiescence(List<Thread> awtThreads, CountDownLatch started) {
        started.countDown();
        boolean interrupted = false;
        for (Thread awtThread : awtThreads) {
            while (awtThread.isAlive()) {
                try {
                    awtThread.join();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        }
        System.out.println(AWT_QUIESCENCE_MARKER);
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void awaitForever(CountDownLatch started) {
        started.countDown();
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private record AwtContext(List<Thread> threads, CountDownLatch releaseEvent) {}
}
