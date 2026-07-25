// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefBrowser_N;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

class CefBrowserRenderProcessUnresponsiveContractTest {
    private static final Class<?> INT_CALLBACK_CLASS = loadClass("org.cef.browser.CefBrowser_N$IntCallback");
    private static final String OPERATION = "render process responsiveness query";

    @Test
    void apiRemainsBinaryCompatibleAndFailsExplicitlyWhenUnsupported() throws Exception {
        Method query = CefBrowser.class.getMethod("isRenderProcessUnresponsive");
        CefBrowser browser = compatibilityBrowser();

        assertTrue(query.isDefault());
        assertEquals(CompletableFuture.class, query.getReturnType());
        assertUnsupported(browser.isRenderProcessUnresponsive(), "isRenderProcessUnresponsive is not supported by this browser");
    }

    @Test
    void nativeDeclarationUsesTheExpectedJniDescriptorAndModifiers() throws Exception {
        Method query = CefBrowser_N.class.getDeclaredMethod("N_IsRenderProcessUnresponsive", INT_CALLBACK_CLASS);
        int modifiers = query.getModifiers();

        assertTrue(Modifier.isPrivate(modifiers));
        assertTrue(Modifier.isFinal(modifiers));
        assertTrue(Modifier.isNative(modifiers));
        assertEquals(void.class, query.getReturnType());
        assertEquals("(Lorg/cef/browser/CefBrowser_N$IntCallback;)V", MethodType.methodType(query.getReturnType(), query.getParameterTypes()).descriptorString());
    }

    @Test
    void uncreatedBrowserRejectsEveryQueryBeforeNativeDispatch() {
        CefBrowser browser = new CefBrowserOsr(null, "about:blank", false, null);

        for (int queryIndex = 0; queryIndex < 8; queryIndex++) {
            CompletableFuture<Boolean> query = browser.isRenderProcessUnresponsive();
            assertUnavailable(query, "Native browser is unavailable for " + OPERATION);
        }
    }

    @Test
    void closedBrowserRejectsEveryQueryBeforeNativeDispatch() {
        CefBrowser browser = new CefBrowserOsr(null, "about:blank", false, null);
        browser.onBeforeClose();

        for (int queryIndex = 0; queryIndex < 8; queryIndex++) {
            CompletableFuture<Boolean> query = browser.isRenderProcessUnresponsive();
            assertUnavailable(query, "Browser closed before " + OPERATION + " completed");
        }
    }

    private static CefBrowser compatibilityBrowser() {
        InvocationHandler handler = (proxy, method, arguments) -> {
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, arguments);
            throw new UnsupportedOperationException(method.getName());
        };
        return (CefBrowser) Proxy.newProxyInstance(CefBrowser.class.getClassLoader(), new Class<?>[] {CefBrowser.class}, handler);
    }

    private static void assertUnsupported(CompletableFuture<?> future, String expectedMessage) {
        assertNotNull(future);
        assertTrue(future.isDone());
        ExecutionException exception = assertThrows(ExecutionException.class, future::get);
        UnsupportedOperationException failure = assertInstanceOf(UnsupportedOperationException.class, exception.getCause());
        assertEquals(expectedMessage, failure.getMessage());
    }

    private static void assertUnavailable(CompletableFuture<?> future, String expectedMessage) {
        assertNotNull(future);
        assertTrue(future.isDone());
        ExecutionException exception = assertThrows(ExecutionException.class, future::get);
        IllegalStateException failure = assertInstanceOf(IllegalStateException.class, exception.getCause());
        assertEquals(expectedMessage, failure.getMessage());
    }

    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
