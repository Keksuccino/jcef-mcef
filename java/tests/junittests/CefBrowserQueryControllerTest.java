// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class CefBrowserQueryControllerTest {
    private static final Class<?> CONTROLLER_CLASS = loadClass("org.cef.browser.CefBrowserQueryController");
    private static final Class<?> QUERY_CLASS = loadClass("org.cef.browser.CefBrowserQueryController$Query");
    private static final Constructor<?> CONSTRUCTOR = getConstructor();
    private static final Method BEGIN = getMethod(CONTROLLER_CLASS, "begin", String.class, boolean.class);
    private static final Method COMPLETE = getMethod(CONTROLLER_CLASS, "complete", QUERY_CLASS, Object.class);
    private static final Method FAIL = getMethod(CONTROLLER_CLASS, "fail", QUERY_CLASS, Throwable.class);
    private static final Method PREPARE_COMPLETION = getMethod(CONTROLLER_CLASS, "prepareCompletion", QUERY_CLASS, Object.class);
    private static final Method CLOSE = getMethod(CONTROLLER_CLASS, "close");
    private static final Method PENDING_COUNT = getMethod(CONTROLLER_CLASS, "pendingCountForTesting");
    private static final Method IS_CLOSED = getMethod(CONTROLLER_CLASS, "isClosedForTesting");
    private static final Method FUTURE = getMethod(QUERY_CLASS, "future");
    private static final Method WAS_ACCEPTED = getMethod(QUERY_CLASS, "wasAccepted");

    @Test
    void completionAndFailureClaimQueriesExactlyOnce() throws Exception {
        Object controller = newController();
        Object successful = begin(controller, "successful query");
        Object failed = begin(controller, "failed query");
        IllegalArgumentException failure = new IllegalArgumentException("expected");

        complete(controller, successful, Integer.valueOf(42));
        fail(controller, successful, new AssertionError("late failure"));
        fail(controller, failed, failure);
        complete(controller, failed, Integer.valueOf(7));

        assertEquals(42, future(successful).get().intValue());
        ExecutionException exception = assertThrows(ExecutionException.class, () -> future(failed).get());
        assertEquals(failure, exception.getCause());
        assertEquals(0, pendingCount(controller));
    }

    @Test
    void closeFailsPendingQueriesAndRejectsNewWork() {
        Object controller = newController();
        Object pending = begin(controller, "pending query");

        close(controller);
        close(controller);
        assertNull(prepareCompletion(controller, pending, Integer.valueOf(1)));
        complete(controller, pending, Integer.valueOf(1));
        Object rejected = begin(controller, "rejected query", true);

        assertExceptionalFailure(future(pending));
        assertExceptionalFailure(future(rejected));
        assertFalse(wasAccepted(rejected));
        assertTrue(isClosed(controller));
        assertEquals(0, pendingCount(controller));
    }

    @Test
    void preparedCompletionBeforeCloseRemainsSuccessfulWhileCloseClaimsPendingWork() throws Exception {
        Object controller = newController();
        Object completed = begin(controller, "completed query");
        Object pending = begin(controller, "pending query");

        Runnable terminalAction = prepareCompletion(controller, completed, Integer.valueOf(23));
        close(controller);
        fail(controller, completed, new AssertionError("late failure"));
        complete(controller, pending, Integer.valueOf(29));
        terminalAction.run();

        assertEquals(23, future(completed).get().intValue());
        assertExceptionalFailure(future(pending));
        assertEquals(0, pendingCount(controller));
        assertTrue(isClosed(controller));
    }

    @Test
    void cancellationAndCallerCompletionReleaseControllerOwnership() throws Exception {
        Object controller = newController();
        Object canceled = begin(controller, "canceled query");
        Object callerCompleted = begin(controller, "caller-completed query");

        assertTrue(future(canceled).cancel(false));
        assertTrue(future(callerCompleted).complete(Integer.valueOf(9)));
        complete(controller, canceled, Integer.valueOf(1));
        fail(controller, callerCompleted, new AssertionError("late failure"));

        assertTrue(future(canceled).isCancelled());
        assertEquals(9, future(callerCompleted).get().intValue());
        assertEquals(0, pendingCount(controller));
    }

    @Test
    void unavailableBrowserRejectsWorkWithoutClosingTheController() {
        Object controller = newController();

        Object rejected = begin(controller, "unavailable query", false);
        Object accepted = begin(controller, "available query", true);

        assertExceptionalFailure(future(rejected));
        assertFalse(wasAccepted(rejected));
        assertTrue(wasAccepted(accepted));
        assertFalse(isClosed(controller));
        complete(controller, accepted, Integer.valueOf(3));
        assertEquals(0, pendingCount(controller));
    }

    @Test
    void concurrentCloseAndCompletionHaveOneTerminalWinner() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 100; ++iteration) {
                Object controller = newController();
                Object query = begin(controller, "racing query");
                CountDownLatch start = new CountDownLatch(1);
                Runnable completionTask = () -> {
                    await(start);
                    complete(controller, query, Integer.valueOf(17));
                };
                Runnable closeTask = () -> {
                    await(start);
                    close(controller);
                };
                CompletableFuture<Void> completion = CompletableFuture.runAsync(completionTask, executor);
                CompletableFuture<Void> close = CompletableFuture.runAsync(closeTask, executor);

                start.countDown();
                CompletableFuture.allOf(completion, close).get(5, TimeUnit.SECONDS);
                try {
                    assertEquals(17, future(query).get(5, TimeUnit.SECONDS).intValue());
                } catch (ExecutionException exception) {
                    assertInstanceOf(IllegalStateException.class, exception.getCause());
                }
                assertEquals(0, pendingCount(controller));
                assertTrue(isClosed(controller));
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static Object newController() {
        try {
            return CONSTRUCTOR.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Object begin(Object controller, String operation) {
        return begin(controller, operation, true);
    }

    private static Object begin(Object controller, String operation, boolean browserAvailable) {
        return invoke(BEGIN, controller, operation, Boolean.valueOf(browserAvailable));
    }

    private static void complete(Object controller, Object query, Object value) {
        invoke(COMPLETE, controller, query, value);
    }

    private static void fail(Object controller, Object query, Throwable failure) {
        invoke(FAIL, controller, query, failure);
    }

    private static Runnable prepareCompletion(Object controller, Object query, Object value) {
        return (Runnable) invoke(PREPARE_COMPLETION, controller, query, value);
    }

    private static void close(Object controller) {
        invoke(CLOSE, controller);
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<Integer> future(Object query) {
        return (CompletableFuture<Integer>) invoke(FUTURE, query);
    }

    private static boolean wasAccepted(Object query) {
        return ((Boolean) invoke(WAS_ACCEPTED, query)).booleanValue();
    }

    private static int pendingCount(Object controller) {
        return ((Integer) invoke(PENDING_COUNT, controller)).intValue();
    }

    private static boolean isClosed(Object controller) {
        return ((Boolean) invoke(IS_CLOSED, controller)).booleanValue();
    }

    private static void assertExceptionalFailure(CompletableFuture<?> future) {
        ExecutionException exception = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IllegalStateException.class, exception.getCause());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Constructor<?> getConstructor() {
        try {
            Constructor<?> constructor = CONTROLLER_CLASS.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Method getMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
        try {
            Method method = owner.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (IllegalAccessException exception) {
            throw new AssertionError(exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError(cause);
        }
    }
}
