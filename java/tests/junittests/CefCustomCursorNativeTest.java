// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefFrame;
import org.cef.browser.CefPaintElementType;
import org.cef.event.CefMouseEvent;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.misc.CefCursorInfo;
import org.cef.misc.CefCursorType;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@NativeCefTest
class CefCustomCursorNativeTest {
    private static final String TEST_URL = "http://custom-cursor.test/index.html";
    private static final String CURSOR_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAQAAAADCAYAAAC09K7GAAAANklEQVR4nA3IoQEAIAgAQbOZrKAbMARDMAmZxV8v3piycNmkKC3GmLHxUDKMjvOjFC8j69B1ec5sFAtra6Z8AAAAAElFTkSuQmCC";
    private static final String TEST_CONTENT =
            "<html><head><style>html,body{margin:0;width:100%;height:100%;cursor:url('data:image/png;base64,"
            + CURSOR_PNG_BASE64 + "') 2 1,auto}</style></head><body>custom cursor</body></html>";
    private static final byte[] EXPECTED_BGRA = {30, 20, 10, (byte) 255, 31, 20, 50, (byte) 255, 32,
            20, 90, (byte) 255, 33, 20, (byte) 130, (byte) 255, 31, 70, 10, (byte) 255, 32, 70, 50,
            (byte) 255, 33, 70, 90, (byte) 255, 34, 70, (byte) 130, (byte) 255, 32, 120, 10,
            (byte) 255, 33, 120, 50, (byte) 255, 34, 120, 90, (byte) 255, 35, 120, (byte) 130,
            (byte) 255};

    @Test
    void deliversAndOwnsCssCustomCursorMetadataAfterTheNativeCallback() {
        AtomicReference<CefCursorInfo> retainedInfo = new AtomicReference<CefCursorInfo>();
        AtomicReference<CefBrowser> callbackBrowser = new AtomicReference<CefBrowser>();
        AtomicInteger rawCursorType = new AtomicInteger(-1);
        AtomicBoolean loaded = new AtomicBoolean();
        AtomicBoolean mouseMoved = new AtomicBoolean();

        TestFrame frame = TestFrame.createOnEventDispatchThread(() -> new TestFrame() {
            @Override
            protected void setupTest() {
                client_.addDisplayHandler(new CefDisplayHandlerAdapter() {
                    @Override
                    public boolean onCursorChange(CefBrowser browser, int cursorType, CefCursorInfo customCursorInfo) {
                        if (cursorType != CefCursorType.CUSTOM.getId() || customCursorInfo == null
                                || !retainedInfo.compareAndSet(null, customCursorInfo))
                            return false;
                        callbackBrowser.set(browser);
                        rawCursorType.set(cursorType);
                        terminateTest();
                        return true;
                    }
                });

                addResource(TEST_URL, TEST_CONTENT, "text/html");
                CursorProbeBrowser browser = new CursorProbeBrowser(client_, TEST_URL);
                browser.addOnPaintListener(event -> {
                    // OSR cursor hit-testing is reliable only after the forced post-load view
                    // paint; moving at load completion races renderer presentation and times out.
                    if (!event.getPopup() && loaded.get() && mouseMoved.compareAndSet(false, true)) browser.moveMouse(8, 8);
                });
                browser_ = browser;
                browser.createImmediately();
                super.setupTest();
            }

            @Override
            public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                if (browser != browser_ || !frame.isMain()) return;
                loaded.set(true);
                browser.invalidate(CefPaintElementType.PET_VIEW);
            }
        });

        try {
            frame.awaitCompletion();

            CefCursorInfo cursorInfo = retainedInfo.get();
            assertNotNull(cursorInfo, "Expected a custom cursor callback before the bounded frame timeout");
            assertSame(frame.browser_, callbackBrowser.get());
            assertEquals(CefCursorType.CUSTOM.getId(), rawCursorType.get());
            assertEquals(2, cursorInfo.getHotspotX());
            assertEquals(1, cursorInfo.getHotspotY());
            assertEquals(1.0f, cursorInfo.getImageScaleFactor());
            assertEquals(4, cursorInfo.getWidth());
            assertEquals(3, cursorInfo.getHeight());
            ByteBuffer retainedPixels = cursorInfo.getBuffer();
            byte[] actualPixels = new byte[retainedPixels.remaining()];
            retainedPixels.get(actualPixels);
            assertArrayEquals(EXPECTED_BGRA, actualPixels);
            assertTrue(retainedPixels.isReadOnly());
        } finally {
            frame.terminateTest();
            frame.awaitCompletion();
        }
    }

    private static final class CursorProbeBrowser extends CefBrowserOsr {
        CursorProbeBrowser(CefClient client, String url) {
            super(client, url, false, null);
            updateViewGeometry(0, 0, 64, 64, new Point(0, 0));
        }

        void moveMouse(int x, int y) {
            sendMouseEvent(new CefMouseEvent(CefMouseEvent.MOUSE_MOVED, x, y, 0, 0, 0));
        }
    }
}
