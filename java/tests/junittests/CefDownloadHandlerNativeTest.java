// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefClient;
import org.cef.CefRequestContextSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefBrowser_N;
import org.cef.browser.CefFrame;
import org.cef.browser.CefRequestContext;
import org.cef.callback.CefBeforeDownloadCallback;
import org.cef.callback.CefDownloadItem;
import org.cef.callback.CefDownloadItemCallback;
import org.cef.event.CefMouseEvent;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefDownloadHandlerAdapter;
import org.cef.handler.CefLoadHandler.ErrorCode;
import org.cef.handler.CefRequestHandler.TerminationStatus;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

@NativeCefTest
class CefDownloadHandlerNativeTest {
    private static final Method IS_ON_CEF_UI_THREAD = getCefUiThreadMethod();
    private static final String PAGE_PATH = "/page.html";
    private static final String FIRST_DOWNLOAD_PATH = "/first.bin";
    private static final String SECOND_DOWNLOAD_PATH = "/second.bin";
    private static final String PAGE_READY_TITLE = "download-relay:ready";
    private static final String FIRST_FOCUS_READY_TITLE = "download-relay:focus:first";
    private static final String SECOND_FOCUS_READY_TITLE = "download-relay:focus:second";
    private static final String COMPLETION_READY_TITLE = "download-relay:complete";
    private static final String PAGE_CONTENT =
            "<!doctype html><html><head><meta charset='utf-8'><title>download-relay:loading</title><link rel='icon' href='data:,'>"
            + "<style>html,body{margin:0;width:100%;height:100%;}a{position:absolute;left:0;width:200px;height:80px;display:block;}#first{top:0;}#second{top:100px;}</style></head>"
            + "<body><a id='first' href='" + FIRST_DOWNLOAD_PATH
            + "'>First download</a><a id='second' href='" + SECOND_DOWNLOAD_PATH
            + "'>Second download</a>"
            + "<script>window.prepareDownloadLink=id=>{const link=document.getElementById(id);const awaitFocus=()=>{link.focus();if(document.hasFocus()&&document.activeElement===link){requestAnimationFrame(()=>requestAnimationFrame(()=>{document.title='download-relay:focus:'+id;}));return;}requestAnimationFrame(awaitFocus);};awaitFocus();};window.acknowledgeDownloadRelay=()=>requestAnimationFrame(()=>requestAnimationFrame(()=>{document.title='download-relay:complete';}));requestAnimationFrame(()=>requestAnimationFrame(()=>{document.title='download-relay:ready';}));</script></body></html>";

    private enum Phase {
        WAITING_FOR_PAGE,
        PREPARING_FIRST,
        WAITING_FOR_FIRST_DOWNLOAD,
        REPLACING_HANDLER,
        PREPARING_SECOND,
        WAITING_FOR_SECOND_DOWNLOAD,
        WAITING_FOR_COMPLETION_ACK,
        COMPLETE
    }

    private enum HandlerRole { FIRST, IGNORED, REPLACEMENT }

    private record DownloadSnapshot(CefBrowser browser, String url, String requestMethod,
            long nativeRef, int order, boolean cefUiThread) {}

    @Test
    void clickedDownloadsRelayExactDecisionsAcrossRemovalAndReplacement() throws Exception {
        AtomicInteger responseOrder = new AtomicInteger();
        LoopbackHttpServer server = null;
        TestFrame frame = null;
        DownloadController controller = null;
        Throwable failure = null;
        try {
            server = new LoopbackHttpServer(
                    3, (requestLine, output) -> serveResponse(responseOrder, requestLine, output));
            controller = new DownloadController(
                    server.url(FIRST_DOWNLOAD_PATH), server.url(SECOND_DOWNLOAD_PATH));
            frame = createFrame(controller, server.url(PAGE_PATH));

            frame.awaitCompletion();
            controller.assertCompleted();
            server.awaitHealthy();
            assertEquals(3, responseOrder.get());
        } catch (Throwable throwable) {
            failure = throwable;
        } finally {
            TestFrame cleanupFrame = frame;
            LoopbackHttpServer cleanupServer = server;
            failure = runAndCollectFailure(failure, () -> terminateAndAwait(cleanupFrame));
            failure = runAndCollectFailure(failure, () -> closeServer(cleanupServer));
        }
        rethrowFailure(failure);
    }

    private static TestFrame createFrame(DownloadController controller, String pageUrl) {
        return TestFrame.createOnEventDispatchThread(() -> new TestFrame() {
            private CefRequestContext requestContext_;

            @Override
            protected void setupTest() {
                controller.attachClient(client_, this::terminateTest);
                client_.addDownloadHandler(controller.firstHandler());
                client_.addDownloadHandler(controller.ignoredHandler());
                client_.addDisplayHandler(new CefDisplayHandlerAdapter() {
                    @Override
                    public void onTitleChange(CefBrowser browser, String title) {
                        controller.onTitleChange(browser, title);
                    }
                });
                requestContext_ =
                        CefRequestContext.createContext(new CefRequestContextSettings(), null);
                assertNotNull(requestContext_);
                browser_ = new DownloadProbeBrowser(client_, pageUrl, requestContext_);
                controller.attachBrowser((DownloadProbeBrowser) browser_);
                browser_.createImmediately();
                super.setupTest();
            }

            @Override
            public void onLoadError(CefBrowser browser, CefFrame frame, ErrorCode errorCode,
                    String errorText, String failedUrl) {
                if (browser == browser_ && frame.isMain()
                        && !controller.isExpectedDeniedDownloadAbort(errorCode, failedUrl))
                    controller.fail(new AssertionError("Download test page failed to load: "
                            + errorCode + ", " + errorText + ", " + failedUrl));
            }

            @Override
            public void onRenderProcessTerminated(CefBrowser browser, TerminationStatus status,
                    int errorCode, String errorString) {
                if (browser == browser_)
                    controller.fail(new AssertionError("Download test renderer terminated: "
                            + status + ", " + errorCode + ", " + errorString));
            }

            @Override
            protected void cleanupTest() {
                try {
                    if (requestContext_ != null) requestContext_.dispose();
                } finally {
                    super.cleanupTest();
                }
            }
        });
    }

    private static void serveResponse(
            AtomicInteger responseOrder, String requestLine, OutputStream output) throws Exception {
        int order = responseOrder.incrementAndGet();
        if (("GET " + PAGE_PATH + " HTTP/1.1").equals(requestLine)) {
            assertEquals(1, order);
            LoopbackHttpServer.writeResponse(output, "200 OK",
                    "Cache-Control: no-store\r\nContent-Type: text/html; charset=utf-8\r\n",
                    PAGE_CONTENT.getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (("GET " + FIRST_DOWNLOAD_PATH + " HTTP/1.1").equals(requestLine)) {
            assertEquals(2, order);
            LoopbackHttpServer.writeResponse(output, "200 OK",
                    "Cache-Control: no-store\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=\"first.bin\"\r\n",
                    "first denied download".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (("GET " + SECOND_DOWNLOAD_PATH + " HTTP/1.1").equals(requestLine)) {
            assertEquals(3, order);
            LoopbackHttpServer.writeResponse(output, "200 OK",
                    "Cache-Control: no-store\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=\"second.bin\"\r\n",
                    "second denied download".getBytes(StandardCharsets.UTF_8));
            return;
        }
        throw new AssertionError("Unexpected download-test HTTP request: " + requestLine);
    }

    private static boolean isOnCefUiThread() {
        try {
            return ((Boolean) IS_ON_CEF_UI_THREAD.invoke(null)).booleanValue();
        } catch (IllegalAccessException exception) {
            throw new AssertionError("Unable to invoke the CEF UI-thread probe", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            if (cause instanceof Error error) throw error;
            throw new AssertionError("CEF UI-thread probe failed", cause);
        }
    }

    private static Method getCefUiThreadMethod() {
        try {
            Method method = CefBrowser_N.class.getDeclaredMethod("N_IsOnCefUiThreadForTesting");
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void awaitNativeRefCleared(CefClient client) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (client.getNativeRef("CefDownloadHandler") != 0) {
            if (System.nanoTime() >= deadline)
                throw new AssertionError(
                        "CefDownloadHandler native reference was not released during terminal cleanup");
            Thread.yield();
        }
    }

    private static Throwable runAndCollectFailure(
            Throwable firstFailure, ThrowingOperation operation) {
        try {
            operation.run();
        } catch (Throwable failure) {
            if (firstFailure == null) return failure;
            if (firstFailure != failure) firstFailure.addSuppressed(failure);
        }
        return firstFailure;
    }

    private static void terminateAndAwait(TestFrame frame) {
        if (frame == null) return;
        frame.terminateTest();
        frame.awaitCompletion();
    }

    private static void closeServer(LoopbackHttpServer server) {
        if (server != null) server.close();
    }

    private static void rethrowFailure(Throwable failure) throws Exception {
        if (failure == null) return;
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
        throw new AssertionError("Download handler native test failed", failure);
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }

    private static final class DownloadController {
        private final String firstUrl_;
        private final String secondUrl_;
        private final AtomicReference<Phase> phase_ =
                new AtomicReference<Phase>(Phase.WAITING_FOR_PAGE);
        private final AtomicReference<Throwable> failure_ = new AtomicReference<Throwable>();
        private final AtomicReference<DownloadSnapshot> firstSnapshot_ =
                new AtomicReference<DownloadSnapshot>();
        private final AtomicReference<DownloadSnapshot> replacementSnapshot_ =
                new AtomicReference<DownloadSnapshot>();
        private final AtomicInteger callbackOrder_ = new AtomicInteger();
        private final AtomicInteger firstCalls_ = new AtomicInteger();
        private final AtomicInteger ignoredCalls_ = new AtomicInteger();
        private final AtomicInteger replacementCalls_ = new AtomicInteger();
        private final AtomicInteger beforeDownloadCalls_ = new AtomicInteger();
        private final AtomicInteger downloadUpdatedCalls_ = new AtomicInteger();
        private final AtomicBoolean replacementQueued_ = new AtomicBoolean();
        private final AtomicBoolean terminationRequested_ = new AtomicBoolean();
        private final RelayHandler firstHandler_ = new RelayHandler(HandlerRole.FIRST);
        private final RelayHandler ignoredHandler_ = new RelayHandler(HandlerRole.IGNORED);
        private final RelayHandler replacementHandler_ = new RelayHandler(HandlerRole.REPLACEMENT);
        private volatile CefClient client_;
        private volatile DownloadProbeBrowser browser_;
        private volatile Runnable terminator_;
        private volatile long retainedNativeRef_;
        private volatile boolean defaultAllowObserved_;

        private DownloadController(String firstUrl, String secondUrl) {
            firstUrl_ = firstUrl;
            secondUrl_ = secondUrl;
        }

        private void attachClient(CefClient client, Runnable terminator) {
            assertTrue(client_ == null);
            assertTrue(terminator_ == null);
            client_ = client;
            terminator_ = terminator;
        }

        private void attachBrowser(DownloadProbeBrowser browser) {
            assertTrue(browser_ == null);
            browser_ = browser;
        }

        private RelayHandler firstHandler() {
            return firstHandler_;
        }

        private RelayHandler ignoredHandler() {
            return ignoredHandler_;
        }

        private boolean isExpectedDeniedDownloadAbort(ErrorCode errorCode, String failedUrl) {
            return errorCode == ErrorCode.ERR_ABORTED
                    && (firstUrl_.equals(failedUrl) || secondUrl_.equals(failedUrl));
        }

        private void onTitleChange(CefBrowser browser, String title) {
            if (browser != browser_ || title == null) return;
            try {
                if (PAGE_READY_TITLE.equals(title)
                        && phase_.compareAndSet(Phase.WAITING_FOR_PAGE, Phase.PREPARING_FIRST)) {
                    enqueue(() -> browser_.prepareLink("first"));
                } else if (FIRST_FOCUS_READY_TITLE.equals(title)
                        && phase_.compareAndSet(
                                Phase.PREPARING_FIRST, Phase.WAITING_FOR_FIRST_DOWNLOAD)) {
                    enqueue(() -> browser_.clickLink(20, 20));
                } else if (SECOND_FOCUS_READY_TITLE.equals(title)
                        && phase_.compareAndSet(
                                Phase.PREPARING_SECOND, Phase.WAITING_FOR_SECOND_DOWNLOAD)) {
                    enqueue(() -> browser_.clickLink(20, 120));
                } else if (COMPLETION_READY_TITLE.equals(title)
                        && phase_.compareAndSet(Phase.WAITING_FOR_COMPLETION_ACK, Phase.COMPLETE)) {
                    requestTermination();
                }
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        private boolean onCanDownload(
                HandlerRole role, CefBrowser browser, String url, String requestMethod) {
            try {
                if (role == HandlerRole.IGNORED) {
                    ignoredCalls_.incrementAndGet();
                    throw new AssertionError(
                            "The second addDownloadHandler call replaced the first delegate");
                }
                if (role == HandlerRole.FIRST) {
                    assertEquals(1, firstCalls_.incrementAndGet());
                    assertTrue(phase_.compareAndSet(
                            Phase.WAITING_FOR_FIRST_DOWNLOAD, Phase.REPLACING_HANDLER));
                    DownloadSnapshot snapshot = snapshot(browser, url, requestMethod);
                    assertEquals(firstUrl_, url);
                    assertTrue(firstSnapshot_.compareAndSet(null, snapshot));
                    retainedNativeRef_ = snapshot.nativeRef();
                    queueReplacement();
                    return false;
                }

                assertEquals(1, replacementCalls_.incrementAndGet());
                assertTrue(phase_.compareAndSet(
                        Phase.WAITING_FOR_SECOND_DOWNLOAD, Phase.WAITING_FOR_COMPLETION_ACK));
                DownloadSnapshot snapshot = snapshot(browser, url, requestMethod);
                assertEquals(secondUrl_, url);
                assertEquals(retainedNativeRef_, snapshot.nativeRef());
                assertTrue(replacementSnapshot_.compareAndSet(null, snapshot));
                enqueue(browser_::acknowledgeCompletion);
            } catch (Throwable failure) {
                fail(failure);
            }
            return false;
        }

        private DownloadSnapshot snapshot(CefBrowser browser, String url, String requestMethod) {
            assertSame(browser_, browser);
            assertEquals("GET", requestMethod);
            boolean cefUiThread = isOnCefUiThread();
            assertTrue(cefUiThread,
                    "CefDownloadHandler callbacks must run on the CEF browser-process UI thread");
            long nativeRef = client_.getNativeRef("CefDownloadHandler");
            assertTrue(
                    nativeRef != 0, "The native download relay must remain alive during callbacks");
            return new DownloadSnapshot(browser, url, requestMethod, nativeRef,
                    callbackOrder_.incrementAndGet(), cefUiThread);
        }

        private void queueReplacement() {
            if (!replacementQueued_.compareAndSet(false, true)) return;
            enqueue(this::replaceHandler);
        }

        private void replaceHandler() {
            try {
                assertEquals(Phase.REPLACING_HANDLER, phase_.get());
                client_.removeDownloadHandler();
                assertEquals(retainedNativeRef_, client_.getNativeRef("CefDownloadHandler"));
                defaultAllowObserved_ =
                        client_.canDownload(browser_, "https://default.test/no-handler", "GET");
                assertTrue(defaultAllowObserved_);
                client_.addDownloadHandler(replacementHandler_);
                assertTrue(phase_.compareAndSet(Phase.REPLACING_HANDLER, Phase.PREPARING_SECOND));
                browser_.prepareLink("second");
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        private void unexpectedBeforeDownload(HandlerRole role) {
            beforeDownloadCalls_.incrementAndGet();
            fail(new AssertionError(role
                    + " handler received onBeforeDownloadWithDecision after canDownload returned false"));
        }

        private void unexpectedDownloadUpdate(HandlerRole role) {
            downloadUpdatedCalls_.incrementAndGet();
            fail(new AssertionError(
                    role + " handler received onDownloadUpdated after canDownload returned false"));
        }

        private void enqueue(Runnable operation) {
            try {
                SwingUtilities.invokeLater(() -> {
                    try {
                        operation.run();
                    } catch (Throwable failure) {
                        fail(failure);
                    }
                });
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        private void requestTermination() {
            if (terminationRequested_.compareAndSet(false, true) && terminator_ != null)
                terminator_.run();
        }

        private void fail(Throwable failure) {
            failure_.compareAndSet(null, failure);
            requestTermination();
        }

        private void assertCompleted() {
            Throwable failure = failure_.get();
            if (failure != null)
                throw new AssertionError("Clicked-download native relay failed", failure);
            assertEquals(Phase.COMPLETE, phase_.get());
            DownloadSnapshot first = firstSnapshot_.get();
            DownloadSnapshot replacement = replacementSnapshot_.get();
            assertNotNull(first);
            assertNotNull(replacement);
            assertSame(browser_, first.browser());
            assertSame(browser_, replacement.browser());
            assertEquals(firstUrl_, first.url());
            assertEquals(secondUrl_, replacement.url());
            assertEquals("GET", first.requestMethod());
            assertEquals("GET", replacement.requestMethod());
            assertTrue(first.cefUiThread());
            assertTrue(replacement.cefUiThread());
            assertEquals(1, first.order());
            assertEquals(2, replacement.order());
            assertTrue(first.nativeRef() != 0);
            assertEquals(first.nativeRef(), replacement.nativeRef());
            assertEquals(1, firstCalls_.get());
            assertEquals(0, ignoredCalls_.get());
            assertEquals(1, replacementCalls_.get());
            assertEquals(0, beforeDownloadCalls_.get());
            assertEquals(0, downloadUpdatedCalls_.get());
            assertTrue(defaultAllowObserved_);
            awaitNativeRefCleared(client_);
        }

        private final class RelayHandler extends CefDownloadHandlerAdapter {
            private final HandlerRole role_;

            private RelayHandler(HandlerRole role) {
                role_ = role;
            }

            @Override
            public boolean canDownload(CefBrowser browser, String url, String requestMethod) {
                return onCanDownload(role_, browser, url, requestMethod);
            }

            @Override
            public boolean onBeforeDownloadWithDecision(CefBrowser browser,
                    CefDownloadItem downloadItem, String suggestedName,
                    CefBeforeDownloadCallback callback) {
                unexpectedBeforeDownload(role_);
                return false;
            }

            @Override
            public void onDownloadUpdated(CefBrowser browser, CefDownloadItem downloadItem,
                    CefDownloadItemCallback callback) {
                unexpectedDownloadUpdate(role_);
            }
        }
    }

    private static final class DownloadProbeBrowser extends CefBrowserOsr {
        private DownloadProbeBrowser(
                CefClient client, String url, CefRequestContext requestContext) {
            super(client, url, false, requestContext);
            updateViewGeometry(0, 0, 800, 600, new Point(0, 0));
        }

        private void prepareLink(String linkId) {
            setFocus(true);
            CefFrame frame = getMainFrame();
            if (frame == null)
                throw new AssertionError(
                        "OSR browser has no main frame while preparing a download click");
            try {
                frame.executeJavaScript(
                        "window.prepareDownloadLink('" + linkId + "');", getURL(), 1);
            } finally {
                frame.dispose();
            }
        }

        private void clickLink(int x, int y) {
            // A native press/release pair establishes the user gesture required by CanDownload.
            // JavaScript click() and CefBrowser.startDownload() bypass this admission callback.
            sendMouseEvent(new CefMouseEvent(1, x, y, 1, 0, CefMouseEvent.BUTTON1_MASK));
            sendMouseEvent(new CefMouseEvent(0, x, y, 1, 0, 0));
        }

        private void acknowledgeCompletion() {
            CefFrame frame = getMainFrame();
            if (frame == null)
                throw new AssertionError(
                        "OSR browser has no main frame while acknowledging download completion");
            try {
                frame.executeJavaScript("window.acknowledgeDownloadRelay();", getURL(), 1);
            } finally {
                frame.dispose();
            }
        }
    }
}
