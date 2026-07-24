// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

class CefBrowserCreationControllerTest {
    private static final Constructor<?> CONSTRUCTOR = getConstructor();
    private static final Method BEGIN = getMethod("begin", boolean.class, boolean.class);
    private static final Method SUCCEEDED = getMethod("succeeded");
    private static final Method FAILED = getMethod("failed");
    private static final Method IS_CREATED = getMethod("isCreated");
    private static final Method IS_NEW = getMethod("isNew");
    private static final Method IS_PENDING = getMethod("isPending");

    @Test
    void concurrentBeginAllowsOnlyOnePendingNativeRequest() throws Exception {
        Object controller = newController();
        int workerCount = 16;
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < workerCount; ++i) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return begin(controller, false, false);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int accepted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS).booleanValue()) ++accepted;
            }
            assertEquals(1, accepted);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void nativeFailureReturnsControllerToRetryableState() {
        Object controller = newController();
        assertTrue(begin(controller, false, false));
        assertFalse(((Boolean) invoke(IS_CREATED, controller)).booleanValue());
        assertTrue(((Boolean) invoke(IS_PENDING, controller)).booleanValue());
        invoke(FAILED, controller);
        assertTrue(((Boolean) invoke(IS_NEW, controller)).booleanValue());
        assertTrue(begin(controller, false, false));
    }

    @Test
    void acceptedCreationAndEndingLifecycleRejectFurtherRequests() {
        Object controller = newController();
        assertFalse(begin(controller, true, false));
        assertFalse(begin(controller, false, true));
        assertTrue(begin(controller, false, false));
        assertFalse(((Boolean) invoke(IS_CREATED, controller)).booleanValue());
        assertTrue(((Boolean) invoke(IS_PENDING, controller)).booleanValue());
        invoke(SUCCEEDED, controller);
        assertTrue(((Boolean) invoke(IS_CREATED, controller)).booleanValue());
        assertFalse(begin(controller, false, false));
    }

    private static Constructor<?> getConstructor() {
        try {
            Constructor<?> constructor =
                    Class.forName("org.cef.browser.CefBrowserCreationController")
                            .getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Method getMethod(String name, Class<?>... parameterTypes) {
        try {
            Method method = CONSTRUCTOR.getDeclaringClass().getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Object newController() {
        try {
            return CONSTRUCTOR.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static boolean begin(
            Object controller, boolean nativeBrowserExists, boolean lifecycleEnding) {
        return ((Boolean) invoke(BEGIN, controller, Boolean.valueOf(nativeBrowserExists),
                        Boolean.valueOf(lifecycleEnding)))
                .booleanValue();
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (IllegalAccessException exception) {
            throw new AssertionError(exception);
        } catch (InvocationTargetException exception) {
            throw new AssertionError(exception.getCause());
        }
    }
}
