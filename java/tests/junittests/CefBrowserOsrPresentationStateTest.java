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
import org.cef.browser.CefPaintElementType;
import org.cef.browser.CefPaintEvent;
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
    private static final int RESUMED_WIDTH = 37;
    private static final int RESUMED_HEIGHT = 29;

    @Test
    void mcefStyleImmediateOsrBrowserAcceptsCompletePresentationStateSequence() throws Exception {
        CompletableFuture<McefStyleBrowser> browserCreated = new CompletableFuture<McefStyleBrowser>();
        CompletableFuture<CefPaintEvent> initialPaint = new CompletableFuture<CefPaintEvent>();
        CompletableFuture<CefPaintEvent> resumedPaint = new CompletableFuture<CefPaintEvent>();
        AtomicBoolean observePaint = new AtomicBoolean();
        TestFrame frame = TestFrame.createOnEventDispatchThread(() -> new TestFrame() {
            @Override
            protected void setupTest() {
                McefStyleBrowser browser = new McefStyleBrowser(client_);
                browser.addOnPaintListener(event -> {
                    if (event.getPopup()) return;
                    initialPaint.complete(event);
                    if (observePaint.get() && event.getWidth() == RESUMED_WIDTH && event.getHeight() == RESUMED_HEIGHT)
                        resumedPaint.complete(event);
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
            browser.resize(RESUMED_WIDTH, RESUMED_HEIGHT);

            observePaint.set(true);
            browser.setWindowVisibility(true);
            // CEF stops layout and painting while hidden, so repeat the resize notification after
            // WasHidden(false). The geometry update above still verifies that resizing while hidden
            // is safe, while this notification deterministically schedules the resumed frame.
            browser.notifyResized(RESUMED_WIDTH, RESUMED_HEIGHT);
            browser.notifyScreenInfoChanged();
            browser.invalidate(CefPaintElementType.PET_VIEW);

            CefPaintEvent paint = await(resumedPaint);
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
            notifyResized(width, height);
        }

        private void notifyResized(int width, int height) {
            wasResized(width, height);
        }
    }
}
