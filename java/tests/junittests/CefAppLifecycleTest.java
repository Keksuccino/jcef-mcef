// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefApp;
import org.cef.handler.CefAppHandler;
import org.cef.handler.CefAppHandlerAdapter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class CefAppLifecycleTest {
    private static final Method DIRECT_LIFECYCLE_METHOD = getDirectLifecycleMethod();
    private static final Method DEDICATED_LIFECYCLE_METHOD = getDedicatedLifecycleMethod();

    @Test
    void macLifecycleStaysOnCallerThreadForNativeAppKitMarshalling() {
        assertTrue(usesDirectLifecycleThread(false, true));
        assertTrue(usesDirectLifecycleThread(true, true));
        assertFalse(usesDedicatedLifecycleThread(false, true));
        assertFalse(usesDedicatedLifecycleThread(true, true));
    }

    @Test
    void nonMacExternalPumpUsesDedicatedLifecycleThread() {
        assertFalse(usesDirectLifecycleThread(false, false));
        assertFalse(usesDirectLifecycleThread(true, false));
        assertFalse(usesDedicatedLifecycleThread(false, false));
        assertTrue(usesDedicatedLifecycleThread(true, false));
    }

    @Test
    void lifecycleExecutorSerializesCrossThreadAndReentrantCalls() throws Exception {
        Class<?> executorClass = Class.forName("org.cef.CefLifecycleExecutor");
        java.lang.reflect.Constructor<?> constructor = executorClass.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        Object executor = constructor.newInstance("CefLifecycleExecutorTest");
        Method call = executorClass.getDeclaredMethod("call", Callable.class);
        Method close = executorClass.getDeclaredMethod("close");
        call.setAccessible(true);
        close.setAccessible(true);
        try {
            AtomicReference<Thread> firstOwner = new AtomicReference<Thread>();
            CountDownLatch firstDone = new CountDownLatch(1);
            Thread caller = new Thread(() -> {
                firstOwner.set((Thread) invoke(call, executor, (Callable<Thread>) Thread::currentThread));
                firstDone.countDown();
            }, "lifecycle-test-caller");
            caller.start();
            assertTrue(firstDone.await(5, TimeUnit.SECONDS));
            caller.join(5000);

            Thread nestedOwner = (Thread) invoke(call, executor, (Callable<Thread>) () -> (Thread) invoke(call, executor, (Callable<Thread>) Thread::currentThread));
            assertTrue(firstOwner.get() == nestedOwner);
        } finally {
            invoke(close, executor);
        }
    }

    @Test
    void detachedLifecycleWorkEscapesAwtAndOwnsShutdownToCompletion() throws Exception {
        Class<?> executorClass = Class.forName("org.cef.CefLifecycleExecutor");
        Method executeDetached = executorClass.getDeclaredMethod("executeDetached", String.class, Runnable.class);
        executeDetached.setAccessible(true);
        AtomicReference<Thread> worker = new AtomicReference<Thread>();
        AtomicReference<Boolean> workerWasAwt = new AtomicReference<Boolean>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);

        Runnable shutdownWork = () -> {
            try {
                workerWasAwt.set(Boolean.valueOf(javax.swing.SwingUtilities.isEventDispatchThread()));
                entered.countDown();
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                completed.countDown();
            }
        };
        javax.swing.SwingUtilities.invokeAndWait(() -> worker.set((Thread) invoke(executeDetached, null, "CefDetachedLifecycleTest", shutdownWork)));

        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertFalse(worker.get().isDaemon());
        assertTrue(worker.get().isAlive());
        release.countDown();
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        worker.get().join(5000);
        assertFalse(worker.get().isAlive());
        assertFalse(workerWasAwt.get().booleanValue());
    }

    @Test
    void concurrentStateNotificationsPreserveSerializedEnqueueOrder() throws Exception {
        Method enqueue = CefApp.class.getDeclaredMethod("enqueueStateNotificationForTesting", CefApp.CefAppState.class, CefAppHandler.class);
        enqueue.setAccessible(true);
        List<CefApp.CefAppState> notifications = new ArrayList<CefApp.CefAppState>();
        CountDownLatch delivered = new CountDownLatch(2);
        CefAppHandler handler = new CefAppHandlerAdapter(null) {
            @Override
            public void stateHasChanged(CefApp.CefAppState state) {
                synchronized (notifications) {
                    notifications.add(state);
                }
                delivered.countDown();
            }
        };
        CountDownLatch start = new CountDownLatch(1);
        long[] sequences = new long[2];
        Thread initializing = notificationThread(enqueue, handler, CefApp.CefAppState.INITIALIZING, sequences, 0, start);
        Thread shuttingDown = notificationThread(enqueue, handler, CefApp.CefAppState.SHUTTING_DOWN, sequences, 1, start);
        initializing.start();
        shuttingDown.start();
        start.countDown();
        initializing.join(5000);
        shuttingDown.join(5000);

        assertFalse(initializing.isAlive());
        assertFalse(shuttingDown.isAlive());
        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        List<CefApp.CefAppState> expected = sequences[0] < sequences[1]
                ? List.of(CefApp.CefAppState.INITIALIZING, CefApp.CefAppState.SHUTTING_DOWN)
                : List.of(CefApp.CefAppState.SHUTTING_DOWN, CefApp.CefAppState.INITIALIZING);
        synchronized (notifications) {
            assertEquals(expected, notifications);
        }
    }

    @Test
    void lifecycleFailureCollectorAttemptsEveryOperationAndSuppressesFollowers() throws Exception {
        Class<?> executorClass = Class.forName("org.cef.CefLifecycleExecutor");
        Method collect = executorClass.getDeclaredMethod("runAndCollectFailure", Throwable.class, Runnable.class);
        Method rethrow = executorClass.getDeclaredMethod("rethrowFailure", Throwable.class);
        collect.setAccessible(true);
        rethrow.setAccessible(true);
        List<Integer> attempts = new ArrayList<Integer>();
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second");

        Throwable failure = (Throwable) invoke(collect, null, null, (Runnable) () -> {
            attempts.add(Integer.valueOf(1));
            throw first;
        });
        failure = (Throwable) invoke(collect, null, failure, (Runnable) () -> {
            attempts.add(Integer.valueOf(2));
            throw second;
        });
        failure = (Throwable) invoke(collect, null, failure, (Runnable) () -> attempts.add(Integer.valueOf(3)));

        assertEquals(List.of(1, 2, 3), attempts);
        assertSame(first, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertSame(second, failure.getSuppressed()[0]);
        Throwable collectedFailure = failure;
        assertSame(first, assertThrows(IllegalStateException.class, () -> invoke(rethrow, null, collectedFailure)));
    }

    private static Thread notificationThread(Method enqueue, CefAppHandler handler, CefApp.CefAppState state, long[] sequences, int index, CountDownLatch start) {
        return new Thread(() -> {
            try {
                start.await();
                sequences[index] = ((Long) invoke(enqueue, null, state, handler)).longValue();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }, "state-notification-test-" + index);
    }

    private static Method getDirectLifecycleMethod() {
        try {
            Method method = CefApp.class.getDeclaredMethod("usesDirectLifecycleThread", boolean.class, boolean.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Method getDedicatedLifecycleMethod() {
        try {
            Method method = CefApp.class.getDeclaredMethod("usesDedicatedLifecycleThread", boolean.class, boolean.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static boolean usesDirectLifecycleThread(boolean externallyDrivenMessagePump, boolean macintosh) {
        try {
            return ((Boolean) DIRECT_LIFECYCLE_METHOD.invoke(null, externallyDrivenMessagePump, macintosh)).booleanValue();
        } catch (IllegalAccessException exception) {
            throw new AssertionError(exception);
        } catch (InvocationTargetException exception) {
            throw new AssertionError(exception.getCause());
        }
    }

    private static boolean usesDedicatedLifecycleThread(boolean externallyDrivenMessagePump, boolean macintosh) {
        try {
            return ((Boolean) DEDICATED_LIFECYCLE_METHOD.invoke(null, externallyDrivenMessagePump, macintosh)).booleanValue();
        } catch (IllegalAccessException exception) {
            throw new AssertionError(exception);
        } catch (InvocationTargetException exception) {
            throw new AssertionError(exception.getCause());
        }
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (IllegalAccessException exception) {
            throw new AssertionError(exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException) throw(RuntimeException) cause;
            if (cause instanceof Error) throw(Error) cause;
            throw new AssertionError(cause);
        }
    }
}
