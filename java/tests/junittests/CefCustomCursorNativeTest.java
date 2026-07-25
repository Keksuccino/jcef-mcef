// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefFrame;
import org.cef.browser.CefPaintElementType;
import org.cef.browser.CefPaintEvent;
import org.cef.event.CefMouseEvent;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLoadHandler.ErrorCode;
import org.cef.handler.CefRequestHandler.TerminationStatus;
import org.cef.misc.CefCursorInfo;
import org.cef.misc.CefCursorType;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

@NativeCefTest
class CefCustomCursorNativeTest {
    private static final String TEST_URL = "http://custom-cursor.test/index.html";
    private static final String READY_TITLE = "jcef-custom-cursor:ready";
    private static final String PRIME_MOVE_TITLE = "jcef-custom-cursor:prime-move";
    private static final String PROBE_MOVE_TITLE = "jcef-custom-cursor:probe-move";
    private static final String FAILURE_TITLE_PREFIX = "jcef-custom-cursor:failure:";
    private static final String CURSOR_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAQAAAADCAYAAAC09K7GAAAANklEQVR4nA3IoQEAIAgAQbOZrKAbMARDMAmZxV8v3piycNmkKC3GmLHxUDKMjvOjFC8j69B1ec5sFAtra6Z8AAAAAElFTkSuQmCC";
    private static final int SENTINEL_RED = 17;
    private static final int SENTINEL_GREEN = 34;
    private static final int SENTINEL_BLUE = 51;
    private static final String SENTINEL_CSS = "rgb(" + SENTINEL_RED + "," + SENTINEL_GREEN + "," + SENTINEL_BLUE + ")";
    private static final long SENTINEL_BGRA = SENTINEL_BLUE | (long) SENTINEL_GREEN << 8 | (long) SENTINEL_RED << 16 | 0xFFL << 24;
    private static final int VIEW_SIZE = 64;
    private static final int PRIME_X = 8;
    private static final int PRIME_Y = 48;
    private static final int PROBE_X = 48;
    private static final int PROBE_Y = 48;
    private static final int SENTINEL_SAMPLE_INSET = 8;
    private static final String TEST_CONTENT = "<!doctype html><html><head><meta charset='utf-8'><style>html,body{margin:0;width:100%;height:100%;background:rgb(1,2,3);cursor:auto}</style></head><body>custom cursor<script>(()=>{" + "const cursorUrl=\"data:image/png;base64," + CURSOR_PNG_BASE64 + "\";" + "const signalFailure=error=>{document.title='" + FAILURE_TITLE_PREFIX + "'+(error&&error.message?error.message:String(error));};" + "window.addEventListener('mousemove',event=>{if(!event.isTrusted)return;if(event.clientX===" + PRIME_X + "&&event.clientY===" + PRIME_Y + ")document.title='" + PRIME_MOVE_TITLE + "';else if(event.clientX===" + PROBE_X + "&&event.clientY===" + PROBE_Y + ")document.title='" + PROBE_MOVE_TITLE + "';});" + "const cursorImage=new Image();cursorImage.src=cursorUrl;cursorImage.decode().then(()=>{" + "const cursorValue=\"url('\"+cursorUrl+\"') 2 1, auto\";" + "document.documentElement.style.cursor=cursorValue;document.body.style.cursor=cursorValue;" + "document.documentElement.style.backgroundColor='" + SENTINEL_CSS + "';document.body.style.backgroundColor='" + SENTINEL_CSS + "';" + "requestAnimationFrame(()=>requestAnimationFrame(()=>{document.title='" + READY_TITLE + "';}));" + "}).catch(signalFailure);})();</script></body></html>";
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
        AtomicBoolean rendererReady = new AtomicBoolean();
        AtomicBoolean inputStarted = new AtomicBoolean();
        AtomicBoolean primeMoveAcknowledged = new AtomicBoolean();
        AtomicBoolean probeMoveQueued = new AtomicBoolean();
        AtomicBoolean probeMoveAcknowledged = new AtomicBoolean();
        AtomicBoolean inputCompleted = new AtomicBoolean();
        AtomicBoolean completionRequested = new AtomicBoolean();
        CursorDiagnostics diagnostics = new CursorDiagnostics();

        TestFrame frame = TestFrame.createOnEventDispatchThread(() -> new TestFrame() {
            private void completeIfReady() {
                if (inputCompleted.get() && retainedInfo.get() != null && completionRequested.compareAndSet(false, true)) {
                    diagnostics.recordStage("completion requested after input and custom cursor");
                    terminateTest();
                }
            }

            private void enqueuePrimeMove(CursorProbeBrowser browser) {
                if (!inputStarted.compareAndSet(false, true)) return;
                try {
                    // OnPaint is a native render callback. Hand input back to a later EDT turn so
                    // CEF can finish releasing the frame before JNI reenters browser input.
                    SwingUtilities.invokeLater(() -> dispatchPrimeMove(browser));
                    diagnostics.recordStage("prime MOUSE_MOVED queued after sentinel paint");
                } catch (Throwable throwable) {
                    failInput("prime MOUSE_MOVED enqueue threw ", throwable);
                }
            }

            private void dispatchPrimeMove(CursorProbeBrowser browser) {
                try {
                    browser.moveMouse(PRIME_X, PRIME_Y);
                    diagnostics.recordStage("prime MOUSE_MOVED sent at " + PRIME_X + "," + PRIME_Y);
                } catch (Throwable throwable) {
                    failInput("prime MOUSE_MOVED dispatch threw ", throwable);
                }
            }

            private void enqueueProbeMove(CursorProbeBrowser browser) {
                if (!probeMoveQueued.compareAndSet(false, true)) return;
                try {
                    // The renderer's trusted priming move is the processing barrier. Queue the
                    // distinct probe move only after returning from that title callback.
                    SwingUtilities.invokeLater(() -> dispatchProbeMove(browser));
                    diagnostics.recordStage("probe MOUSE_MOVED queued after renderer prime acknowledgement");
                } catch (Throwable throwable) {
                    failInput("probe MOUSE_MOVED enqueue threw ", throwable);
                }
            }

            private void dispatchProbeMove(CursorProbeBrowser browser) {
                try {
                    browser.moveMouse(PROBE_X, PROBE_Y);
                    diagnostics.recordStage("probe MOUSE_MOVED sent at " + PROBE_X + "," + PROBE_Y);
                } catch (Throwable throwable) {
                    failInput("probe MOUSE_MOVED dispatch threw ", throwable);
                }
            }

            private void failInput(String description, Throwable throwable) {
                diagnostics.recordFailure(description + throwable);
                terminateTest();
            }

            @Override
            protected void setupTest() {
                client_.addDisplayHandler(new CefDisplayHandlerAdapter() {
                    @Override
                    public void onTitleChange(CefBrowser browser, String title) {
                        diagnostics.recordStage("title matchingBrowser=" + (browser == browser_) + ", value=" + title);
                        if (browser != browser_) return;
                        if (READY_TITLE.equals(title) && rendererReady.compareAndSet(false, true)) {
                            diagnostics.recordStage("renderer ready title received");
                            try {
                                browser.invalidate(CefPaintElementType.PET_VIEW);
                                diagnostics.recordStage("PET_VIEW invalidation requested");
                            } catch (Throwable throwable) {
                                diagnostics.recordFailure("PET_VIEW invalidation threw " + throwable);
                                terminateTest();
                            }
                        } else if (PRIME_MOVE_TITLE.equals(title) && primeMoveAcknowledged.compareAndSet(false, true)) {
                            diagnostics.recordStage("renderer acknowledged trusted prime mousemove");
                            enqueueProbeMove((CursorProbeBrowser) browser);
                        } else if (PROBE_MOVE_TITLE.equals(title) && probeMoveAcknowledged.compareAndSet(false, true)) {
                            diagnostics.recordStage("renderer acknowledged trusted probe mousemove");
                            inputCompleted.set(true);
                            completeIfReady();
                        } else if (title != null && title.startsWith(FAILURE_TITLE_PREFIX)) {
                            diagnostics.recordFailure("renderer readiness JavaScript failed: " + title.substring(FAILURE_TITLE_PREFIX.length()));
                            terminateTest();
                        }
                    }

                    @Override
                    public boolean onCursorChange(CefBrowser browser, int cursorType, CefCursorInfo customCursorInfo) {
                        diagnostics.recordCursorCallback(browser == browser_, cursorType, customCursorInfo != null);
                        if (browser != browser_ || cursorType != CefCursorType.CUSTOM.getId() || customCursorInfo == null) return false;
                        if (retainedInfo.compareAndSet(null, customCursorInfo)) {
                            callbackBrowser.set(browser);
                            rawCursorType.set(cursorType);
                            diagnostics.recordStage("owned custom cursor snapshot retained");
                        }
                        completeIfReady();
                        return true;
                    }
                });

                addResource(TEST_URL, TEST_CONTENT, "text/html");
                CursorProbeBrowser browser = new CursorProbeBrowser(client_, TEST_URL);
                browser.addOnPaintListener(event -> {
                    if (event.getPopup()) {
                        diagnostics.recordPopupPaint();
                        return;
                    }
                    long sampledPixel = sampleProbePixel(event);
                    diagnostics.recordViewPaint(event.getWidth(), event.getHeight(), sampledPixel);
                    // A title callback and an arbitrary later paint do not prove that Blink has
                    // presented the decoded cursor style. The sentinel is changed in the same
                    // renderer task as the cursor and makes the accepted OSR frame unambiguous.
                    if (!rendererReady.get() || sampledPixel != SENTINEL_BGRA) return;
                    diagnostics.recordSentinelPaint();
                    enqueuePrimeMove(browser);
                });
                browser_ = browser;
                browser.createImmediately();
                super.setupTest();
            }

            @Override
            public void onAfterCreated(CefBrowser browser) {
                super.onAfterCreated(browser);
                if (browser == browser_) diagnostics.recordStage("browser created");
            }

            @Override
            public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                if (browser == browser_ && frame.isMain()) diagnostics.recordStage("main-frame load ended status=" + httpStatusCode);
            }

            @Override
            public void onLoadError(CefBrowser browser, CefFrame frame, ErrorCode errorCode, String errorText, String failedUrl) {
                if (browser != browser_ || !frame.isMain()) return;
                diagnostics.recordFailure("main-frame load failed code=" + errorCode + ", text=" + errorText + ", url=" + failedUrl);
                terminateTest();
            }

            @Override
            public void onRenderProcessTerminated(CefBrowser browser, TerminationStatus status, int errorCode, String errorString) {
                if (browser != browser_) return;
                diagnostics.recordFailure("renderer terminated status=" + status + ", code=" + errorCode + ", error=" + errorString);
                terminateTest();
            }
        });

        try {
            awaitCompletion(frame, diagnostics);

            assertFalse(diagnostics.hasFailure(), "Custom cursor readiness failed; " + diagnostics.snapshot());
            CefCursorInfo cursorInfo = retainedInfo.get();
            assertNotNull(cursorInfo, "Expected a custom cursor callback before the bounded frame timeout; " + diagnostics.snapshot());
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
            awaitCompletion(frame, diagnostics);
        }
    }

    private static void awaitCompletion(TestFrame frame, CursorDiagnostics diagnostics) {
        try {
            frame.awaitCompletion();
        } catch (AssertionError error) {
            throw new AssertionError(error.getMessage() + "; " + diagnostics.snapshot(), error);
        }
    }

    private static long sampleProbePixel(CefPaintEvent event) {
        int sampleX = event.getWidth() - SENTINEL_SAMPLE_INSET;
        int sampleY = event.getHeight() - SENTINEL_SAMPLE_INSET;
        if (sampleX < 0 || sampleY < 0) return -1;
        ByteBuffer frame = event.getRenderedFrame();
        if (frame == null) return -1;
        // Sampling relative to the backing frame remains below the page text if OSR pixel density
        // changes, while the opaque body background makes the expected BGRA value exact.
        long offset = ((long) sampleY * event.getWidth() + sampleX) * 4;
        if (offset < 0 || offset + 4 > frame.limit()) return -1;
        int index = (int) offset;
        return Byte.toUnsignedLong(frame.get(index)) | Byte.toUnsignedLong(frame.get(index + 1)) << 8 | Byte.toUnsignedLong(frame.get(index + 2)) << 16 | Byte.toUnsignedLong(frame.get(index + 3)) << 24;
    }

    private static final class CursorProbeBrowser extends CefBrowserOsr {
        CursorProbeBrowser(CefClient client, String url) {
            super(client, url, false, null);
            updateViewGeometry(0, 0, VIEW_SIZE, VIEW_SIZE, new Point(0, 0));
        }

        void moveMouse(int x, int y) {
            sendMouseEvent(new CefMouseEvent(CefMouseEvent.MOUSE_MOVED, x, y, 0, 0, 0));
        }
    }

    private static final class CursorDiagnostics {
        private final long startedNanos = System.nanoTime();
        private final AtomicReference<String> failure = new AtomicReference<String>();
        private final AtomicReference<String> lastViewPaintSize = new AtomicReference<String>("<none>");
        private final AtomicLong lastViewPaintPixel = new AtomicLong(-1);
        private final AtomicInteger viewPaints = new AtomicInteger();
        private final AtomicInteger popupPaints = new AtomicInteger();
        private final AtomicInteger sentinelPaints = new AtomicInteger();
        private final ConcurrentLinkedQueue<String> stages = new ConcurrentLinkedQueue<String>();
        private final ConcurrentLinkedQueue<String> cursorCallbacks = new ConcurrentLinkedQueue<String>();

        void recordStage(String detail) {
            stages.add(stage(detail));
        }

        void recordPopupPaint() {
            popupPaints.incrementAndGet();
        }

        void recordViewPaint(int width, int height, long sampledPixel) {
            viewPaints.incrementAndGet();
            lastViewPaintSize.set(width + "x" + height);
            lastViewPaintPixel.set(sampledPixel);
        }

        void recordSentinelPaint() {
            if (sentinelPaints.incrementAndGet() == 1) recordStage("first sentinel paint sampled expected BGRA");
        }

        void recordCursorCallback(boolean matchingBrowser, int cursorType, boolean hasCustomInfo) {
            cursorCallbacks.add(stage("matchingBrowser=" + matchingBrowser + ", type=" + cursorType + ", customInfo=" + hasCustomInfo));
        }

        void recordFailure(String detail) {
            String timedDetail = stage(detail);
            stages.add(timedDetail);
            failure.compareAndSet(null, timedDetail);
        }

        boolean hasFailure() {
            return failure.get() != null;
        }

        String snapshot() {
            return "CursorDiagnostics{elapsedMs=" + elapsedMillis() + ", stages=" + stages + ", viewPaints=" + viewPaints.get() + ", popupPaints=" + popupPaints.get() + ", sentinelPaints=" + sentinelPaints.get() + ", lastViewPaint=" + lastViewPaintSize.get() + "/" + formatPixel(lastViewPaintPixel.get()) + ", cursorCallbacks=" + cursorCallbacks + ", failure=" + failure.get() + "}";
        }

        private String stage(String detail) {
            return elapsedMillis() + "ms:" + detail;
        }

        private long elapsedMillis() {
            return (System.nanoTime() - startedNanos) / 1_000_000;
        }

        private static String formatPixel(long pixel) {
            if (pixel < 0) return "<unavailable>";
            String hex = Long.toHexString(pixel).toUpperCase();
            return "0x" + "0".repeat(8 - hex.length()) + hex;
        }
    }
}
