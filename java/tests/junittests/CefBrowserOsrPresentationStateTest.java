// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefBrowser_N;
import org.cef.browser.CefFrame;
import org.cef.browser.CefPaintElementType;
import org.cef.browser.CefPaintEvent;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@NativeCefTest
class CefBrowserOsrPresentationStateTest {
    private static final long FUTURE_TIMEOUT_SECONDS = 10;
    private static final int HIDDEN_WIDTH = 37;
    private static final int HIDDEN_HEIGHT = 29;
    private static final int RESUMED_WIDTH = 43;
    private static final int RESUMED_HEIGHT = 31;
    private static final String RENDERER_VISIBLE_TITLE = "jcef-osr-renderer-visible";

    @Test
    void mcefStyleImmediateOsrBrowserAcceptsCompletePresentationStateSequence() throws Exception {
        CompletableFuture<McefStyleBrowser> browserCreated = new CompletableFuture<McefStyleBrowser>();
        CompletableFuture<CefPaintEvent> initialPaint = new CompletableFuture<CefPaintEvent>();
        CompletableFuture<Void> rendererVisible = new CompletableFuture<Void>();
        CompletableFuture<CefPaintEvent> resizedPaint = new CompletableFuture<CefPaintEvent>();
        AtomicBoolean observeResizedPaint = new AtomicBoolean();
        TestFrame frame = TestFrame.createOnEventDispatchThread(() -> new TestFrame() {
            @Override
            protected void setupTest() {
                client_.addDisplayHandler(new CefDisplayHandlerAdapter() {
                    @Override
                    public void onTitleChange(CefBrowser browser, String title) {
                        if (browser == browser_ && RENDERER_VISIBLE_TITLE.equals(title)) rendererVisible.complete(null);
                    }
                });
                McefStyleBrowser browser = new McefStyleBrowser(client_);
                browser.addOnPaintListener(event -> {
                    if (event.getPopup()) return;
                    initialPaint.complete(event);
                    if (observeResizedPaint.get() && event.getWidth() == RESUMED_WIDTH && event.getHeight() == RESUMED_HEIGHT)
                        resizedPaint.complete(event);
                });
                browser_ = browser;
                browser_.createImmediately();
                super.setupTest();
            }

            @Override
            public void onAfterCreated(CefBrowser browser) {
                super.onAfterCreated(browser);
                if (browser == browser_) browserCreated.complete((McefStyleBrowser) browser);
            }
        });

        try {
            McefStyleBrowser browser = await(browserCreated);
            await(initialPaint);
            NullPointerException nullType = assertThrows(NullPointerException.class, () -> browser.invalidate(null));
            assertEquals("type", nullType.getMessage());
            assertNativeInvalidateRejects(browser, -1);
            assertNativeInvalidateRejects(browser, 2);

            browser.setWindowVisibility(false);
            browser.notifyScreenInfoChanged();
            browser.invalidate(CefPaintElementType.PET_VIEW);
            browser.invalidate(CefPaintElementType.PET_POPUP);
            browser.resize(HIDDEN_WIDTH, HIDDEN_HEIGHT);

            browser.setWindowVisibility(true);
            browser.notifyScreenInfoChanged();
            browser.invalidate(CefPaintElementType.PET_VIEW);
            requestVisibleRendererFrame(browser);
            await(rendererVisible);

            // WasHidden(false) is asynchronous and CEF suppresses animation frames while hidden.
            // The renderer callback therefore acknowledges visible state without accepting a stale
            // paint that was already queued before WasHidden(true). Use a new geometry after that
            // acknowledgement because CEF may legitimately coalesce a redundant resize.
            observeResizedPaint.set(true);
            browser.resize(RESUMED_WIDTH, RESUMED_HEIGHT);
            browser.invalidate(CefPaintElementType.PET_VIEW);

            CefPaintEvent paint = await(resizedPaint);
            assertSame(browser, paint.getBrowser());
            assertFalse(paint.getPopup());
            assertNotNull(paint.getDirtyRects());
            assertEquals(RESUMED_WIDTH, paint.getWidth());
            assertEquals(RESUMED_HEIGHT, paint.getHeight());
        } finally {
            frame.terminateTest();
            frame.awaitCompletion();
        }
    }

    private static void requestVisibleRendererFrame(CefBrowser browser) {
        CefFrame mainFrame = browser.getMainFrame();
        if (mainFrame == null) throw new AssertionError("OSR browser has no main frame after its initial paint");
        try {
            mainFrame.executeJavaScript("requestAnimationFrame(()=>{document.title='" + RENDERER_VISIBLE_TITLE + "';});", "about:blank", 1);
        } finally {
            mainFrame.dispose();
        }
    }

    private static void assertNativeInvalidateRejects(CefBrowser browser, int value) throws Exception {
        Method nativeInvalidate = CefBrowser_N.class.getDeclaredMethod("N_InvalidatePaintElement", int.class);
        nativeInvalidate.setAccessible(true);
        InvocationTargetException exception = assertThrows(InvocationTargetException.class, () -> nativeInvalidate.invoke(browser, value));
        assertInstanceOf(IllegalArgumentException.class, exception.getCause());
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static final class McefStyleBrowser extends CefBrowserOsr {
        private McefStyleBrowser(CefClient client) {
            super(client, "about:blank", false, null);
        }

        private void resize(int width, int height) {
            updateViewGeometry(0, 0, width, height, new Point(0, 0));
            wasResized(width, height);
        }
    }
}
