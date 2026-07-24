// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Owns lifecycle and externally driven message-pump calls for a native CEF context. */
final class CefLifecycleExecutor implements AutoCloseable {
    private final AtomicReference<Thread> ownerThread_ = new AtomicReference<Thread>();
    private final AtomicBoolean pumpQueued_ = new AtomicBoolean();
    private final ScheduledThreadPoolExecutor executor_;
    private volatile boolean acceptingTasks_ = true;

    CefLifecycleExecutor(String threadName) {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(() -> {
                ownerThread_.compareAndSet(null, Thread.currentThread());
                runnable.run();
            }, threadName);
            thread.setDaemon(true);
            return thread;
        };
        executor_ = new ScheduledThreadPoolExecutor(1, threadFactory);
        executor_.setRemoveOnCancelPolicy(true);
        executor_.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor_.prestartCoreThread();
    }

    static Thread executeDetached(String threadName, Runnable runnable) {
        Thread thread = new Thread(runnable, threadName);
        // macOS disposal cannot wait here: AppKit main may itself be waiting for an EDT callback.
        // Keep the shutdown owner non-daemon so JVM termination cannot cut CefShutdown short after
        // every application thread has otherwise completed.
        thread.setDaemon(false);
        thread.start();
        return thread;
    }

    boolean isOwnerThread() {
        return Thread.currentThread() == ownerThread_.get();
    }

    <T> T call(Callable<T> callable) {
        if (isOwnerThread()) return callInline(callable);
        Future<T> future = executor_.submit(callable);
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return future.get();
                } catch (InterruptedException exception) {
                    // Lifecycle tasks must not be abandoned after submission. In particular, a JVM
                    // shutdown hook may be the only thread keeping the process alive for shutdown.
                    interrupted = true;
                }
            }
        } catch (ExecutionException exception) {
            throw propagate(exception.getCause());
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    void execute(Runnable runnable) {
        if (!acceptingTasks_)
            throw new RejectedExecutionException("CEF lifecycle executor is closed");
        executor_.execute(runnable);
    }

    void schedule(Runnable runnable, long delayMillis) {
        if (!acceptingTasks_)
            throw new RejectedExecutionException("CEF lifecycle executor is closed");
        executor_.schedule(runnable, delayMillis, TimeUnit.MILLISECONDS);
    }

    void executeCoalescedPump(Runnable runnable) {
        scheduleCoalescedPump(runnable, 0);
    }

    void scheduleCoalescedPump(Runnable runnable, long delayMillis) {
        if (!acceptingTasks_ || !pumpQueued_.compareAndSet(false, true)) return;
        try {
            executor_.schedule(() -> {
                // Release the slot before executing so a shutdown pump can schedule its next
                // bounded iteration without accumulating more than one queued pump task.
                pumpQueued_.set(false);
                if (acceptingTasks_) runnable.run();
            }, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException exception) {
            pumpQueued_.set(false);
            if (acceptingTasks_) throw exception;
        }
    }

    private static <T> T callInline(Callable<T> callable) {
        try {
            return callable.call();
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("CEF lifecycle operation failed", exception);
        }
    }

    private static RuntimeException propagate(Throwable throwable) {
        if (throwable instanceof RuntimeException) return (RuntimeException) throwable;
        if (throwable instanceof Error) throw(Error) throwable;
        return new IllegalStateException("CEF lifecycle operation failed", throwable);
    }

    static Throwable runAndCollectFailure(Throwable firstFailure, Runnable operation) {
        try {
            operation.run();
        } catch (Throwable failure) {
            if (firstFailure == null) return failure;
            if (firstFailure != failure) firstFailure.addSuppressed(failure);
        }
        return firstFailure;
    }

    static void rethrowFailure(Throwable failure) {
        if (failure == null) return;
        if (failure instanceof RuntimeException) throw(RuntimeException) failure;
        if (failure instanceof Error) throw(Error) failure;
        throw new IllegalStateException("CEF lifecycle cleanup failed", failure);
    }

    @Override
    public void close() {
        acceptingTasks_ = false;
        executor_.shutdown();
    }
}
