// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.cef.callback.CefResourceReadCallback;
import org.cef.callback.CefResourceSkipCallback;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

class CefResourceCallbackTest {
    private static final Class<?> READ_CLASS = getClass("org.cef.callback.CefResourceReadCallback_N");
    private static final Class<?> SKIP_CLASS = getClass("org.cef.callback.CefResourceSkipCallback_N");
    private static final Method SET_BUFFER_REFS = getMethod(READ_CLASS, "setBufferRefs", long.class, byte[].class);
    private static final Method NORMALIZE_READ = getMethod(READ_CLASS, "normalizeBytesRead", int.class, int.class);
    private static final Method FINALIZE_READ = getMethod(READ_CLASS, "finalize");
    private static final Method SET_BYTES_TO_SKIP = getMethod(SKIP_CLASS, "setBytesToSkip", long.class);
    private static final Method NORMALIZE_SKIP = getMethod(SKIP_CLASS, "normalizeBytesSkipped", long.class, long.class);
    private static final Method FINALIZE_SKIP = getMethod(SKIP_CLASS, "finalize");

    @Test
    void readContinuationClaimsBufferOnlyOnceAcrossThreads() throws Exception {
        CefResourceReadCallback callback = (CefResourceReadCallback) newInstance(READ_CLASS);
        byte[] buffer = new byte[8];
        invoke(SET_BUFFER_REFS, callback, Long.valueOf(0), buffer);
        assertSame(buffer, callback.getBuffer());

        runConcurrently(16, () -> callback.Continue(4));

        assertNull(callback.getBuffer());
        callback.Continue(4);
        assertNull(callback.getBuffer());
    }

    @Test
    void skipContinuationCanOnlyBeConsumedOnceAcrossThreads() throws Exception {
        CefResourceSkipCallback callback = (CefResourceSkipCallback) newInstance(SKIP_CLASS);
        invoke(SET_BYTES_TO_SKIP, callback, Long.valueOf(8));

        runConcurrently(16, () -> callback.Continue(4));
        callback.Continue(4);
    }

    @Test
    void callbackBoundsPreserveDocumentedErrorsAndRejectOversizedSuccess() {
        assertEquals(-2, ((Integer) invoke(NORMALIZE_READ, null, Integer.valueOf(9), Integer.valueOf(8))).intValue());
        assertEquals(-7, ((Integer) invoke(NORMALIZE_READ, null, Integer.valueOf(-7), Integer.valueOf(8))).intValue());
        assertEquals(0L, ((Long) invoke(NORMALIZE_SKIP, null, Long.valueOf(9), Long.valueOf(8))).longValue());
        assertEquals(-7L, ((Long) invoke(NORMALIZE_SKIP, null, Long.valueOf(-7), Long.valueOf(8))).longValue());
    }

    @Test
    void finalizationFailsAndClearsDroppedPendingCallbacksOnlyOnce() {
        CefResourceReadCallback read = (CefResourceReadCallback) newInstance(READ_CLASS);
        invoke(SET_BUFFER_REFS, read, Long.valueOf(0), new byte[8]);
        invoke(FINALIZE_READ, read);
        invoke(FINALIZE_READ, read);
        assertNull(read.getBuffer());

        CefResourceSkipCallback skip = (CefResourceSkipCallback) newInstance(SKIP_CLASS);
        invoke(SET_BYTES_TO_SKIP, skip, Long.valueOf(8));
        invoke(FINALIZE_SKIP, skip);
        invoke(FINALIZE_SKIP, skip);
    }

    private static void runConcurrently(int count, Runnable action) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<Thread>();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        for (int i = 0; i < count; ++i) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    action.run();
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                }
            });
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(5000);
            assertFalse(thread.isAlive(), "Concurrent callback thread did not finish");
        }
        if (failure.get() != null)
            throw new AssertionError("Concurrent callback invocation failed", failure.get());
    }

    private static Class<?> getClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Method getMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            Method method = type.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Object newInstance(Class<?> type) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
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
