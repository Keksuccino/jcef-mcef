// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefFrame;
import org.cef.browser.CefZoomCommand;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@NativeCefTest
class CefBrowserZoomNativeTest {
    private static final long FUTURE_TIMEOUT_SECONDS = 10;
    private static final int MAX_ZOOM_STEPS = 32;
    private static final double ZOOM_TOLERANCE = 0.000_001;
    private static final Method LEGACY_NATIVE_ZOOM_GETTER = getLegacyNativeZoomGetter();
    private static final Method LEGACY_NATIVE_ZOOM_SETTER = getLegacyNativeZoomSetter();

    private record DirectQuerySnapshot(Thread cefUiThread, double defaultLevel, double currentLevel, double legacyLevel, boolean canZoomOut, boolean canResetZoom, boolean canZoomIn) {}

    @Test
    @WindowedCefTest
    void windowedBrowserSupportsDirectAndPostedZoomOperations() throws Exception {
        assertZoomOperations(false);
    }

    @Test
    void offscreenBrowserSupportsDirectAndPostedZoomOperations() throws Exception {
        assertZoomOperations(true);
    }

    @Test
    void uncreatedBrowserRejectsQueriesWithoutChangingLegacyCommandBehavior() {
        CefBrowser browser = new CefBrowserOsr(null, "about:blank", false, null);

        assertUnavailable(browser.canZoom(CefZoomCommand.IN));
        assertUnavailable(browser.getDefaultZoomLevel());
        assertUnavailable(browser.getZoomLevelAsync());
        browser.zoom(CefZoomCommand.OUT);
        browser.zoom(CefZoomCommand.RESET);
        browser.zoom(CefZoomCommand.IN);
        browser.setZoomLevel(1.0);
        assertEquals(0.0, browser.getZoomLevel());
        invokeLegacyNativeZoomSetter(browser, 1.0);
        assertEquals(0.0, invokeLegacyNativeZoomGetter(browser));
        assertThrows(NullPointerException.class, () -> browser.canZoom(null));
        assertThrows(NullPointerException.class, () -> browser.zoom(null));
    }

    private static void assertZoomOperations(boolean offscreen) throws Exception {
        String testUrl = offscreen ? "http://zoom-osr.test/index.html" : "http://zoom-windowed.test/index.html";
        CompletableFuture<CefBrowser> browserCreated = new CompletableFuture<CefBrowser>();
        CompletableFuture<DirectQuerySnapshot> directQueries = new CompletableFuture<DirectQuerySnapshot>();
        Supplier<TestFrame> frameFactory = () -> new TestFrame() {
            @Override
            protected void setupTest() {
                addResource(testUrl, "<html><body>Zoom bridge test</body></html>", "text/html");
                if (offscreen) {
                    browser_ = createOffscreenBrowser(testUrl, null);
                } else {
                    createBrowser(testUrl);
                }
                super.setupTest();
            }

            @Override
            public void onAfterCreated(CefBrowser browser) {
                super.onAfterCreated(browser);
                if (browser == browser_) browserCreated.complete(browser);
            }

            @Override
            public void onLoadEnd(CefBrowser browser, CefFrame cefFrame, int httpStatusCode) {
                super.onLoadEnd(browser, cefFrame, httpStatusCode);
                if (browser != browser_ || !cefFrame.isMain() || directQueries.isDone()) return;
                captureDirectQueries(browser, directQueries);
            }
        };
        TestFrame frame = TestFrame.createOnEventDispatchThread(frameFactory);

        CefBrowser browser = null;
        try {
            browser = await(browserCreated);
            DirectQuerySnapshot direct = await(directQueries);
            assertNotSame(direct.cefUiThread(), Thread.currentThread(), "Worker-thread checks must not run on the CEF UI callback thread");
            assertEquals(direct.defaultLevel(), direct.currentLevel(), ZOOM_TOLERANCE);
            assertEquals(direct.currentLevel(), direct.legacyLevel(), ZOOM_TOLERANCE);
            assertPostedQueriesAndCommands(browser, direct);
        } finally {
            frame.terminateTest();
            frame.awaitCompletion();
        }
        invokeLegacyNativeZoomSetter(browser, 1.0);
        assertEquals(0.0, invokeLegacyNativeZoomGetter(browser));
    }

    private static void captureDirectQueries(CefBrowser browser, CompletableFuture<DirectQuerySnapshot> result) {
        // This test owns a dedicated resource hostname. Resetting on the CEF UI callback establishes
        // deterministic initial state and exercises direct command dispatch after the main frame
        // has become live.
        browser.zoom(CefZoomCommand.RESET);
        CompletableFuture<Double> defaultLevel = browser.getDefaultZoomLevel();
        CompletableFuture<Double> currentLevel = browser.getZoomLevelAsync();
        CompletableFuture<Boolean> canZoomOut = browser.canZoom(CefZoomCommand.OUT);
        CompletableFuture<Boolean> canResetZoom = browser.canZoom(CefZoomCommand.RESET);
        CompletableFuture<Boolean> canZoomIn = browser.canZoom(CefZoomCommand.IN);
        if (!defaultLevel.isDone() || !currentLevel.isDone() || !canZoomOut.isDone() || !canResetZoom.isDone() || !canZoomIn.isDone()) {
            result.completeExceptionally(new AssertionError("Queries invoked from a CEF UI callback must complete directly"));
            return;
        }

        try {
            result.complete(new DirectQuerySnapshot(Thread.currentThread(), defaultLevel.join().doubleValue(), currentLevel.join().doubleValue(), invokeLegacyNativeZoomGetter(browser), canZoomOut.join().booleanValue(), canResetZoom.join().booleanValue(), canZoomIn.join().booleanValue()));
        } catch (Throwable failure) {
            result.completeExceptionally(failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure);
        }
    }

    private static void assertPostedQueriesAndCommands(CefBrowser browser, DirectQuerySnapshot direct) throws Exception {
        assertEquals(direct.defaultLevel(), await(browser.getDefaultZoomLevel()).doubleValue(), ZOOM_TOLERANCE);
        assertEquals(direct.currentLevel(), await(browser.getZoomLevelAsync()).doubleValue(), ZOOM_TOLERANCE);
        assertEquals(direct.canZoomOut(), await(browser.canZoom(CefZoomCommand.OUT)).booleanValue());
        assertEquals(direct.canResetZoom(), await(browser.canZoom(CefZoomCommand.RESET)).booleanValue());
        assertEquals(direct.canZoomIn(), await(browser.canZoom(CefZoomCommand.IN)).booleanValue());

        try {
            browser.zoom(CefZoomCommand.RESET);
            double defaultLevel = await(browser.getDefaultZoomLevel()).doubleValue();
            assertEquals(defaultLevel, await(browser.getZoomLevelAsync()).doubleValue(), ZOOM_TOLERANCE);
            assertFalse(await(browser.canZoom(CefZoomCommand.RESET)).booleanValue());

            assertZoomBoundary(browser, CefZoomCommand.IN);

            browser.zoom(CefZoomCommand.RESET);
            assertEquals(defaultLevel, await(browser.getZoomLevelAsync()).doubleValue(), ZOOM_TOLERANCE);
            assertZoomBoundary(browser, CefZoomCommand.OUT);

            browser.setZoomLevel(1.25);
            assertEquals(1.25, await(browser.getZoomLevelAsync()).doubleValue(), ZOOM_TOLERANCE);
            assertEquals(1.25, browser.getZoomLevel(), ZOOM_TOLERANCE);
            assertEquals(1.25, invokeLegacyNativeZoomGetter(browser), ZOOM_TOLERANCE);
            invokeLegacyNativeZoomSetter(browser, 1.5);
            assertEquals(1.5, await(browser.getZoomLevelAsync()).doubleValue(), ZOOM_TOLERANCE);
            assertEquals(1.5, invokeLegacyNativeZoomGetter(browser), ZOOM_TOLERANCE);
        } finally {
            browser.setZoomLevel(direct.currentLevel());
            assertEquals(direct.currentLevel(), await(browser.getZoomLevelAsync()).doubleValue(), ZOOM_TOLERANCE);
            assertEquals(direct.currentLevel(), browser.getZoomLevel(), ZOOM_TOLERANCE);
        }
    }

    private static void assertZoomBoundary(CefBrowser browser, CefZoomCommand command) throws Exception {
        double previousLevel = await(browser.getZoomLevelAsync()).doubleValue();
        int steps = 0;
        while (await(browser.canZoom(command)).booleanValue()) {
            assertTrue(steps < MAX_ZOOM_STEPS, "Zoom boundary was not reached within the bounded command count");
            browser.zoom(command);
            double currentLevel = await(browser.getZoomLevelAsync()).doubleValue();
            if (command == CefZoomCommand.IN) {
                assertTrue(currentLevel > previousLevel);
            } else {
                assertTrue(currentLevel < previousLevel);
            }
            previousLevel = currentLevel;
            ++steps;
        }

        assertTrue(steps > 0);
        browser.zoom(command);
        assertEquals(previousLevel, await(browser.getZoomLevelAsync()).doubleValue(), ZOOM_TOLERANCE);
    }

    private static void assertUnavailable(CompletableFuture<?> future) {
        assertNotNull(future);
        ExecutionException exception = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IllegalStateException.class, exception.getCause());
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static Method getLegacyNativeZoomGetter() {
        try {
            Method method = org.cef.browser.CefBrowser_N.class.getDeclaredMethod("N_GetZoomLevel");
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Method getLegacyNativeZoomSetter() {
        try {
            Method method = org.cef.browser.CefBrowser_N.class.getDeclaredMethod("N_SetZoomLevel", double.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static double invokeLegacyNativeZoomGetter(CefBrowser browser) {
        try {
            return ((Double) LEGACY_NATIVE_ZOOM_GETTER.invoke(browser)).doubleValue();
        } catch (IllegalAccessException exception) {
            throw new AssertionError(exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError(cause);
        }
    }

    private static void invokeLegacyNativeZoomSetter(CefBrowser browser, double zoomLevel) {
        try {
            LEGACY_NATIVE_ZOOM_SETTER.invoke(browser, Double.valueOf(zoomLevel));
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
