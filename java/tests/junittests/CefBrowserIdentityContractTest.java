// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefBrowser_N;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

class CefBrowserIdentityContractTest {
    @Test
    void publicApiRemainsBinaryCompatibleDefaults() throws Exception {
        assertDefaultBooleanMethod(CefBrowser.class.getMethod("isValid"), "()Z");
        assertDefaultBooleanMethod(CefBrowser.class.getMethod("isSame", CefBrowser.class), "(Lorg/cef/browser/CefBrowser;)Z");
        assertDefaultBooleanMethod(CefBrowser.class.getMethod("isWindowRenderingDisabled"), "()Z");
    }

    @Test
    void nativeDeclarationsHaveExactPrivateFinalDescriptors() throws Exception {
        assertNativeBooleanMethod(CefBrowser_N.class.getDeclaredMethod("N_IsValid"), "()Z");
        assertNativeBooleanMethod(CefBrowser_N.class.getDeclaredMethod("N_IsSame", CefBrowser.class), "(Lorg/cef/browser/CefBrowser;)Z");
        assertNativeBooleanMethod(CefBrowser_N.class.getDeclaredMethod("N_IsWindowRenderingDisabled"), "()Z");
    }

    @Test
    void unsupportedImplementationsFailExplicitlyAndValidateNullBeforeDispatch() {
        CefBrowser browser = compatibilityBrowser();
        CefBrowser peer = compatibilityBrowser();

        assertUnsupported(() -> browser.isValid(), "isValid is not supported by this browser");
        assertUnsupported(() -> browser.isSame(peer), "isSame is not supported by this browser");
        assertUnsupported(() -> browser.isWindowRenderingDisabled(), "isWindowRenderingDisabled is not supported by this browser");
        NullPointerException exception = assertThrows(NullPointerException.class, () -> browser.isSame(null));
        assertEquals("that", exception.getMessage());
    }

    @Test
    void uncreatedAndClosedNativeWrappersReturnFalseWithoutFabricatingState() {
        CefBrowserOsr browser = new CefBrowserOsr(null, "about:blank", false, null);
        CefBrowserOsr peer = new CefBrowserOsr(null, "about:blank", false, null);

        assertUnavailable(browser, peer);
        NullPointerException exception = assertThrows(NullPointerException.class, () -> browser.isSame(null));
        assertEquals("that", exception.getMessage());

        browser.onBeforeClose();

        assertUnavailable(browser, peer);
    }

    private static void assertDefaultBooleanMethod(Method method, String descriptor) {
        assertTrue(method.isDefault());
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertFalse(Modifier.isAbstract(method.getModifiers()));
        assertEquals(boolean.class, method.getReturnType());
        assertEquals(descriptor, MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString());
    }

    private static void assertNativeBooleanMethod(Method method, String descriptor) {
        int modifiers = method.getModifiers();
        assertTrue(Modifier.isPrivate(modifiers));
        assertTrue(Modifier.isFinal(modifiers));
        assertTrue(Modifier.isNative(modifiers));
        assertFalse(Modifier.isStatic(modifiers));
        assertEquals(boolean.class, method.getReturnType());
        assertEquals(descriptor, MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString());
    }

    private static void assertUnavailable(CefBrowser browser, CefBrowser peer) {
        assertFalse(browser.isValid());
        assertFalse(browser.isSame(browser));
        assertFalse(browser.isSame(peer));
        assertFalse(browser.isWindowRenderingDisabled());
    }

    private static CefBrowser compatibilityBrowser() {
        InvocationHandler handler = (proxy, method, arguments) -> {
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, arguments);
            throw new UnsupportedOperationException(method.getName());
        };
        return (CefBrowser) Proxy.newProxyInstance(CefBrowser.class.getClassLoader(), new Class<?>[] {CefBrowser.class}, handler);
    }

    private static void assertUnsupported(Executable executable, String message) {
        UnsupportedOperationException exception = assertThrows(UnsupportedOperationException.class, executable);
        assertEquals(message, exception.getMessage());
    }
}
