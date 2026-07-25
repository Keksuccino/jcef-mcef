// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowser_N;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefZoomCommand;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.DoubleSupplier;

class CefBrowserZoomContractTest {
    @Test
    void zoomCommandValuesMatchCef151() {
        assertArrayEquals(new int[] {0, 1, 2}, Arrays.stream(CefZoomCommand.values()).mapToInt(CefZoomCommand::getValue).toArray());
        assertEquals(CefZoomCommand.OUT, CefZoomCommand.fromValue(0));
        assertEquals(CefZoomCommand.RESET, CefZoomCommand.fromValue(1));
        assertEquals(CefZoomCommand.IN, CefZoomCommand.fromValue(2));
        assertThrows(IllegalArgumentException.class, () -> CefZoomCommand.fromValue(-1));
        assertThrows(IllegalArgumentException.class, () -> CefZoomCommand.fromValue(3));
    }

    @Test
    void newApiMethodsRemainBinaryCompatibleDefaults() throws Exception {
        assertTrue(CefBrowser.class.getMethod("canZoom", CefZoomCommand.class).isDefault());
        assertTrue(CefBrowser.class.getMethod("zoom", CefZoomCommand.class).isDefault());
        assertTrue(CefBrowser.class.getMethod("getDefaultZoomLevel").isDefault());
        assertTrue(CefBrowser.class.getMethod("getZoomLevelAsync").isDefault());
    }

    @Test
    void defaultCurrentQueryBridgesTheLegacySynchronousGetter() throws Exception {
        CefBrowser browser = compatibilityBrowser(1.25);

        CompletableFuture<Double> result = browser.getZoomLevelAsync();

        assertTrue(result.isDone());
        assertEquals(1.25, result.get().doubleValue());
    }

    @Test
    void defaultCurrentQueryConvertsLegacyGetterFailureToFailedFuture() {
        IllegalStateException failure = new IllegalStateException("expected");
        DoubleSupplier throwingZoomLevel = () -> {
            throw failure;
        };
        CefBrowser browser = compatibilityBrowser(throwingZoomLevel);

        CompletableFuture<Double> result = browser.getZoomLevelAsync();

        assertTrue(result.isDone());
        ExecutionException exception = assertThrows(ExecutionException.class, result::get);
        assertEquals(failure, exception.getCause());
    }

    @Test
    void unsupportedDefaultsFailExplicitlyAndValidateNullCommands() throws Exception {
        CefBrowser browser = compatibilityBrowser(0.0);

        assertUnsupported(browser.canZoom(CefZoomCommand.IN));
        assertUnsupported(browser.getDefaultZoomLevel());
        assertThrows(UnsupportedOperationException.class, () -> browser.zoom(CefZoomCommand.RESET));
        assertThrows(NullPointerException.class, () -> browser.canZoom(null));
        assertThrows(NullPointerException.class, () -> browser.zoom(null));
    }

    @Test
    void legacySynchronousZoomDescriptorsArePreserved() throws Exception {
        Method getter = CefBrowser.class.getMethod("getZoomLevel");
        Method setter = CefBrowser.class.getMethod("setZoomLevel", double.class);
        Method nativeGetter = CefBrowser_N.class.getDeclaredMethod("N_GetZoomLevel");
        Method nativeSetter = CefBrowser_N.class.getDeclaredMethod("N_SetZoomLevel", double.class);

        assertEquals(double.class, getter.getReturnType());
        assertEquals(void.class, setter.getReturnType());
        assertEquals(double.class, nativeGetter.getReturnType());
        assertEquals(void.class, nativeSetter.getReturnType());
        assertTrue(Modifier.isNative(nativeGetter.getModifiers()));
        assertTrue(Modifier.isNative(nativeSetter.getModifiers()));
    }

    @Test
    void closedBrowserRejectsEverySharedLifecycleQueryBeforeNativeDispatch() {
        CefBrowserOsr browser = new CefBrowserOsr(null, "about:blank", false, null);

        browser.onBeforeClose();

        assertUnavailable(browser.canZoom(CefZoomCommand.IN));
        assertUnavailable(browser.getDefaultZoomLevel());
        assertUnavailable(browser.getZoomLevelAsync());
        assertUnavailable(browser.isAudioMuted());
        browser.zoom(CefZoomCommand.RESET);
        browser.setZoomLevel(1.0);
        assertEquals(0.0, browser.getZoomLevel());
    }

    private static CefBrowser compatibilityBrowser(double zoomLevel) {
        return compatibilityBrowser(() -> zoomLevel);
    }

    private static CefBrowser compatibilityBrowser(DoubleSupplier zoomLevel) {
        InvocationHandler handler = (proxy, method, arguments) -> {
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, arguments);
            if (method.getName().equals("getZoomLevel")) return Double.valueOf(zoomLevel.getAsDouble());
            throw new UnsupportedOperationException(method.getName());
        };
        return (CefBrowser) Proxy.newProxyInstance(CefBrowser.class.getClassLoader(), new Class<?>[] {CefBrowser.class}, handler);
    }

    private static void assertUnsupported(CompletableFuture<?> future) {
        ExecutionException exception = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(UnsupportedOperationException.class, exception.getCause());
    }

    private static void assertUnavailable(CompletableFuture<?> future) {
        assertTrue(future.isDone());
        ExecutionException exception = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IllegalStateException.class, exception.getCause());
    }
}
