// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

@NativeCefTest
class CefResourceHandlerNativeTest {
    private static final Method TEST_SETUP_CALL = getSetupCallMethod();
    private static final Method TEST_OPEN_CALLBACK_RETENTION = getOpenCallbackRetentionMethod();

    @Test
    void checkedCallbackSetupClearsJavaExceptionsAndReportsFailure() {
        assertFalse(testSetupCall(new ThrowingSetupTarget()));
        assertTrue(testSetupCall(new SuccessfulSetupTarget()));
    }

    @Test
    void openRetainsCallbackOnlyForDeferredHandling() {
        assertFalse(testOpenCallbackRetention(false, false, false));
        assertFalse(testOpenCallbackRetention(true, false, false));
        assertFalse(testOpenCallbackRetention(true, false, true));
        assertFalse(testOpenCallbackRetention(true, true, true));
        assertTrue(testOpenCallbackRetention(true, true, false));
    }

    private static boolean testSetupCall(Object target) {
        try {
            return ((Boolean) TEST_SETUP_CALL.invoke(null, target)).booleanValue();
        } catch (IllegalAccessException exception) {
            throw new AssertionError(exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException) throw(RuntimeException) cause;
            if (cause instanceof Error) throw(Error) cause;
            throw new AssertionError(cause);
        }
    }

    private static Method getSetupCallMethod() {
        try {
            Class<?> callbackClass = Class.forName("org.cef.callback.CefResourceReadCallback_N");
            Method method = callbackClass.getDeclaredMethod("testSetupCallForTesting", Object.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static boolean testOpenCallbackRetention(boolean callSucceeded, boolean result, boolean handleRequest) {
        try {
            return ((Boolean) TEST_OPEN_CALLBACK_RETENTION.invoke(null, Boolean.valueOf(callSucceeded), Boolean.valueOf(result), Boolean.valueOf(handleRequest))).booleanValue();
        } catch (IllegalAccessException exception) {
            throw new AssertionError(exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException) throw(RuntimeException) cause;
            if (cause instanceof Error) throw(Error) cause;
            throw new AssertionError(cause);
        }
    }

    private static Method getOpenCallbackRetentionMethod() {
        try {
            Class<?> callbackClass = Class.forName("org.cef.callback.CefResourceReadCallback_N");
            Method method = callbackClass.getDeclaredMethod("testOpenCallbackRetentionForTesting", boolean.class, boolean.class, boolean.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static final class ThrowingSetupTarget {
        @SuppressWarnings("unused")
        public void setBufferRefs(long nativeBufferRef, byte[] javaBuffer) {
            throw new IllegalStateException("expected setup failure");
        }
    }

    private static final class SuccessfulSetupTarget {
        @SuppressWarnings("unused")
        public void setBufferRefs(long nativeBufferRef, byte[] javaBuffer) {}
    }
}
