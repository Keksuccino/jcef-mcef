// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

@NativeCefTest
class CefBrowserCloseTest {
    private static final Method PREPARE_CLOSE_CONTINUATION = getCloseMethod("prepareCloseContinuation", boolean.class);
    private static final Method CONTINUE_CLOSE = getCloseMethod("continueCloseAfterDoClose");
    private static final Method ABORT_CLOSE_CONTINUATION = getCloseMethod("abortCloseContinuation");

    @Test
    void headlessBrowserAllowsCefToCompleteClose() {
        CefBrowser browser = new CefBrowserOsr(null, "about:blank", false, null);

        assertFalse(browser.doClose());
    }

    @Test
    void closeAllowedBypassesComponentWindowOwner() {
        CefBrowser browser = new ComponentBackedBrowser();
        browser.setCloseAllowed();

        assertFalse(browser.doClose());
    }

    @Test
    void detachedComponentContinuesCloseAfterNativeCallbackReturns() throws Exception {
        CefBrowser browser = new ComponentBackedBrowser();

        assertTrue(browser.doClose());
        SwingUtilities.invokeAndWait(() -> {});
        assertTrue(browser.doClose(), "The AWT continuation must not run from inside native DoClose");
        assertTrue(prepareCloseContinuation(browser, true));
        assertFalse(prepareCloseContinuation(browser, true), "Only one native continuation may be pending");
        continueClose(browser);
        SwingUtilities.invokeAndWait(() -> {});
        assertFalse(browser.doClose());
    }

    @Test
    void uncancelledOrAbortedNativeCloseDoesNotDispatchAwtContinuation() throws Exception {
        CefBrowser browser = new ComponentBackedBrowser();

        assertTrue(browser.doClose());
        assertFalse(prepareCloseContinuation(browser, false));
        continueClose(browser);
        SwingUtilities.invokeAndWait(() -> {});
        assertTrue(browser.doClose());

        assertTrue(prepareCloseContinuation(browser, true));
        abortCloseContinuation(browser);
        continueClose(browser);
        SwingUtilities.invokeAndWait(() -> {});
        assertTrue(browser.doClose());
    }

    private static Method getCloseMethod(String name, Class<?>... parameterTypes) {
        try {
            Method method = org.cef.browser.CefBrowser_N.class.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static boolean prepareCloseContinuation(CefBrowser browser, boolean closeCancelled) {
        return (Boolean) invokeCloseMethod(PREPARE_CLOSE_CONTINUATION, browser, closeCancelled);
    }

    private static void continueClose(CefBrowser browser) {
        invokeCloseMethod(CONTINUE_CLOSE, browser);
    }

    private static void abortCloseContinuation(CefBrowser browser) {
        invokeCloseMethod(ABORT_CLOSE_CONTINUATION, browser);
    }

    private static Object invokeCloseMethod(Method method, CefBrowser browser, Object... arguments) {
        try {
            return method.invoke(browser, arguments);
        } catch (IllegalAccessException exception) {
            throw new AssertionError("Unable to invoke browser close lifecycle method", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError("Browser close lifecycle method failed", cause);
        }
    }

    private static final class ComponentBackedBrowser extends CefBrowserOsr {
        private final JPanel component_ = new JPanel();

        private ComponentBackedBrowser() {
            super(null, "about:blank", false, null);
        }

        @Override
        public Component getUIComponent() {
            return component_;
        }
    }
}
