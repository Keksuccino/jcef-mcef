// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefRequestContextSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefRequestContext;
import org.cef.callback.CefAuthCallback;
import org.cef.callback.CefCallback;
import org.cef.callback.CefSchemeHandlerFactory;
import org.cef.callback.CefURLRequestClient;
import org.cef.handler.CefLoadHandler.ErrorCode;
import org.cef.handler.CefResourceHandler;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;
import org.cef.network.CefURLRequest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@NativeCefTest
@WindowedCefTest
class CefFrameURLRequestNativeTest {
    private static final long CALLBACK_TIMEOUT_SECONDS = 20;
    private static final int NO_SIMPLE_URL_LOADER_RETRIES = CefRequest.CefUrlRequestFlags.UR_FLAG_NO_RETRY_ON_5XX;

    @Test
    void frameCreationPreservesIdentityAndAssociationWithoutChangingStandaloneRouting() throws Exception {
        final String mainUrl = "http://frame-urlrequest.test/main.html";
        final byte[] frameBody = "frame-associated-handler".getBytes(StandardCharsets.UTF_8);
        final byte[] networkBody = "standalone-network".getBytes(StandardCharsets.UTF_8);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        AtomicReference<String> initiatingFrameIdentifier = new AtomicReference<String>();
        AtomicInteger remainingCompletions = new AtomicInteger(2);
        CountDownLatch creationThreadFinished = new CountDownLatch(1);
        AtomicReference<CefRequest> frameRequest = new AtomicReference<CefRequest>();
        AtomicReference<CefRequest> standaloneRequest = new AtomicReference<CefRequest>();
        AtomicReference<CefURLRequest> frameUrlRequest = new AtomicReference<CefURLRequest>();
        AtomicReference<CefURLRequest> standaloneUrlRequest = new AtomicReference<CefURLRequest>();
        AtomicReference<RecordingClient> frameClient = new AtomicReference<RecordingClient>();
        AtomicReference<RecordingClient> standaloneClient = new AtomicReference<RecordingClient>();
        AtomicReference<Thread> creationThread = new AtomicReference<Thread>();
        InvalidCreationClient invalidClient = new InvalidCreationClient();
        CefRequestContext browserContext = CefRequestContext.createContext(new CefRequestContextSettings(), null);
        LoopbackHttpServer server = null;
        TestFrame testFrame = null;
        Throwable primaryFailure = null;
        try {
            assertNotNull(browserContext);
            server = new LoopbackHttpServer(1, (requestLine, output) -> LoopbackHttpServer.writeResponse(output, "200 OK", "Content-Type: text/plain\r\n", networkBody));
            final String requestUrl = server.url("/same-resource");
            AssociatedSchemeFactory schemeFactory = new AssociatedSchemeFactory(requestUrl, frameBody, failure, initiatingFrameIdentifier);
            assertTrue(browserContext.registerSchemeHandlerFactory("http", "127.0.0.1", schemeFactory));
            testFrame = TestFrame.createOnEventDispatchThread(() -> new TestFrame() {
                private final AtomicBoolean started_ = new AtomicBoolean();
                private final AtomicBoolean completionTerminationQueued_ = new AtomicBoolean();

                @Override
                protected void setupTest() {
                    addResource(mainUrl, "<html><body>Frame URL request test</body></html>", "text/html");
                    createBrowser(mainUrl, browserContext);
                    super.setupTest();
                }

                @Override
                public void onLoadingStateChange(CefBrowser browser, boolean isLoading, boolean canGoBack, boolean canGoForward) {
                    if (isLoading || !started_.compareAndSet(false, true)) return;
                    CefFrame frame = browser.getMainFrame();
                    CefFrame disposedFrame = browser.getMainFrame();
                    try {
                        assertNotNull(frame);
                        assertNotNull(disposedFrame);
                        initiatingFrameIdentifier.set(frame.getIdentifier());
                        schemeFactory.setExpectedBrowser(browser);
                        disposedFrame.dispose();
                        Thread creator = new Thread(() -> createRequests(frame, disposedFrame), "frame-urlrequest-create");
                        creator.setDaemon(true);
                        creationThread.set(creator);
                        creator.start();
                    } catch (Throwable throwable) {
                        failure.compareAndSet(null, throwable);
                        if (frame != null) frame.dispose();
                        if (disposedFrame != null) disposedFrame.dispose();
                        creationThreadFinished.countDown();
                        terminateTest();
                    }
                }

                @Override
                protected void cleanupTest() {
                    CefFrameURLRequestNativeTest.dispose(frameUrlRequest.get());
                    CefFrameURLRequestNativeTest.dispose(standaloneUrlRequest.get());
                    CefFrameURLRequestNativeTest.dispose(frameRequest.get());
                    CefFrameURLRequestNativeTest.dispose(standaloneRequest.get());
                    super.cleanupTest();
                }

                private void createRequests(CefFrame frame, CefFrame disposedFrame) {
                    boolean creationFailed = false;
                    try {
                        assertNotNull(initiatingFrameIdentifier.get());
                        verifyInvalidInputs(frame, disposedFrame, requestUrl, invalidClient);

                        CefRequest associatedRequest = createRequest(requestUrl);
                        CefRequest unassociatedRequest = createRequest(requestUrl);
                        frameRequest.set(associatedRequest);
                        standaloneRequest.set(unassociatedRequest);
                        RecordingClient associatedClient = new RecordingClient(associatedRequest, frameBody, failure, this::requestCompleted);
                        RecordingClient unassociatedClient = new RecordingClient(unassociatedRequest, networkBody, failure, this::requestCompleted);
                        frameClient.set(associatedClient);
                        standaloneClient.set(unassociatedClient);

                        CefURLRequest associatedUrlRequest = frame.createURLRequest(associatedRequest, associatedClient);
                        assertNotNull(associatedUrlRequest);
                        frameUrlRequest.set(associatedUrlRequest);
                        associatedClient.publishReturnedURLRequest(associatedUrlRequest);
                        assertSame(associatedRequest, associatedUrlRequest.getRequest());
                        assertSame(associatedClient, associatedUrlRequest.getClient());
                        assertTrue(associatedRequest.isReadOnly());

                        CefURLRequest unassociatedUrlRequest = CefURLRequest.create(unassociatedRequest, unassociatedClient);
                        assertNotNull(unassociatedUrlRequest);
                        standaloneUrlRequest.set(unassociatedUrlRequest);
                        unassociatedClient.publishReturnedURLRequest(unassociatedUrlRequest);
                        assertSame(unassociatedRequest, unassociatedUrlRequest.getRequest());
                        assertSame(unassociatedClient, unassociatedUrlRequest.getClient());
                        assertTrue(unassociatedRequest.isReadOnly());
                    } catch (Throwable throwable) {
                        creationFailed = true;
                        failure.compareAndSet(null, throwable);
                    } finally {
                        if (frame != null) frame.dispose();
                        if (disposedFrame != null) disposedFrame.dispose();
                        creationThreadFinished.countDown();
                        if (creationFailed)
                            terminateTest();
                        else
                            terminateIfComplete();
                    }
                }

                private void requestCompleted() {
                    remainingCompletions.decrementAndGet();
                    terminateIfComplete();
                }

                private void terminateIfComplete() {
                    if (remainingCompletions.get() != 0 || creationThreadFinished.getCount() != 0) return;
                    if (completionTerminationQueued_.compareAndSet(false, true)) terminateTest();
                }
            });

            testFrame.awaitCompletion();
            assertTrue(creationThreadFinished.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS), "Timed out waiting for the off-CEF Java creator thread");
            assertNoFailure(failure);
            server.awaitHealthy();
            assertEquals(1, schemeFactory.calls_.get(), "Only the frame-associated request should use the browser request context");
            assertEquals(0, invalidClient.completionCount_.get());
            assertNotNull(frameClient.get());
            assertNotNull(standaloneClient.get());
            frameClient.get().assertSuccessful();
            standaloneClient.get().assertSuccessful();
        } catch (Throwable throwable) {
            primaryFailure = throwable;
        } finally {
            CleanupState cleanup = new CleanupState(primaryFailure);
            cleanup.awaitCreatorThread(creationThread, creationThreadFinished, "the off-CEF Java creator thread", false);
            cleanup.terminateAndAwait(testFrame);
            cleanup.awaitCreatorThread(creationThread, creationThreadFinished, "the off-CEF Java creator thread", true);
            cleanup.closeServer(server);
            cleanup.disposeRequestContext(browserContext);
            primaryFailure = cleanup.finish();
        }
        rethrowFailure(primaryFailure);
    }

    @Test
    void pendingFrameRequestCanCancelAndDisposeReentrantlyExactlyOnce() throws Exception {
        final String mainUrl = "http://frame-urlrequest.test/cancel-main.html";
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        CountDownLatch requestArrived = new CountDownLatch(1);
        CountDownLatch cancelReturned = new CountDownLatch(1);
        CountDownLatch creatorFinished = new CountDownLatch(1);
        AtomicReference<CefRequest> javaRequest = new AtomicReference<CefRequest>();
        AtomicReference<CefURLRequest> javaUrlRequest = new AtomicReference<CefURLRequest>();
        AtomicReference<CancelClient> javaClient = new AtomicReference<CancelClient>();
        AtomicReference<Thread> creationThread = new AtomicReference<Thread>();

        LoopbackHttpServer server = null;
        TestFrame testFrame = null;
        Throwable primaryFailure = null;
        try {
            server = new LoopbackHttpServer(1, (requestLine, output) -> {
                requestArrived.countDown();
                awaitCallbackLatch(cancelReturned, "the pending frame request cancellation");
            });
            final String requestUrl = server.url("/pending-cancel");
            testFrame = TestFrame.createOnEventDispatchThread(() -> new TestFrame() {
                private final AtomicBoolean started_ = new AtomicBoolean();

                @Override
                protected void setupTest() {
                    addResource(mainUrl, "<html><body>Frame URL request cancellation</body></html>", "text/html");
                    createBrowser(mainUrl);
                    super.setupTest();
                }

                @Override
                public void onLoadingStateChange(CefBrowser browser, boolean isLoading, boolean canGoBack, boolean canGoForward) {
                    if (isLoading || !started_.compareAndSet(false, true)) return;
                    CefFrame frame = browser.getMainFrame();
                    try {
                        assertNotNull(frame);
                        Thread creator = new Thread(() -> createAndCancel(frame), "frame-urlrequest-cancel");
                        creator.setDaemon(true);
                        creationThread.set(creator);
                        creator.start();
                    } catch (Throwable throwable) {
                        failure.compareAndSet(null, throwable);
                        cancelReturned.countDown();
                        creatorFinished.countDown();
                        if (frame != null) frame.dispose();
                        terminateTest();
                    }
                }

                @Override
                protected void cleanupTest() {
                    CefFrameURLRequestNativeTest.dispose(javaUrlRequest.get());
                    CefFrameURLRequestNativeTest.dispose(javaRequest.get());
                    super.cleanupTest();
                }

                private void createAndCancel(CefFrame frame) {
                    try {
                        CefRequest request = createRequest(requestUrl);
                        CancelClient client = new CancelClient(request, failure, this::terminateTest);
                        javaRequest.set(request);
                        javaClient.set(client);
                        CefURLRequest urlRequest = frame.createURLRequest(request, client);
                        javaUrlRequest.set(urlRequest);
                        client.setExpectedURLRequest(urlRequest);
                        assertNotNull(urlRequest);
                        awaitCallbackLatch(requestArrived, "the pending associated network request");
                        urlRequest.cancel();
                    } catch (Throwable throwable) {
                        failure.compareAndSet(null, throwable);
                        terminateTest();
                    } finally {
                        cancelReturned.countDown();
                        if (frame != null) frame.dispose();
                        creatorFinished.countDown();
                    }
                }
            });

            testFrame.awaitCompletion();
            assertTrue(creatorFinished.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS), "Timed out waiting for the cancellation creator thread");
            assertNoFailure(failure);
            server.awaitHealthy();
            assertNotNull(javaClient.get());
            javaClient.get().assertCanceled();
        } catch (Throwable throwable) {
            primaryFailure = throwable;
        } finally {
            CleanupState cleanup = new CleanupState(primaryFailure);
            requestArrived.countDown();
            cancelReturned.countDown();
            cleanup.awaitCreatorThread(creationThread, creatorFinished, "the cancellation creator thread", false);
            cleanup.terminateAndAwait(testFrame);
            cleanup.awaitCreatorThread(creationThread, creatorFinished, "the cancellation creator thread", true);
            cleanup.closeServer(server);
            primaryFailure = cleanup.finish();
        }
        rethrowFailure(primaryFailure);
    }

    private static void verifyInvalidInputs(CefFrame frame, CefFrame disposedFrame, String requestUrl, InvalidCreationClient client) {
        assertThrows(NullPointerException.class, () -> frame.createURLRequest(null, client));
        CefRequest nullClientRequest = createRequest(requestUrl);
        CefRequest disposedRequest = createRequest(requestUrl);
        CefRequest disposedFrameRequest = createRequest(requestUrl);
        CefRequest invalidUrlRequest = CefRequest.create();
        CefURLRequest invalidUrlResult = null;
        try {
            assertNotNull(invalidUrlRequest);
            invalidUrlRequest.setMethod("GET");
            invalidUrlRequest.setURL("");
            assertThrows(NullPointerException.class, () -> frame.createURLRequest(nullClientRequest, null));
            disposedRequest.dispose();
            assertNull(frame.createURLRequest(disposedRequest, client));
            assertNull(disposedFrame.createURLRequest(disposedFrameRequest, client));
            assertFalse(invalidUrlRequest.isReadOnly());
            invalidUrlResult = frame.createURLRequest(invalidUrlRequest, client);
            assertNull(invalidUrlResult);
            assertTrue(invalidUrlRequest.isReadOnly());
        } finally {
            dispose(invalidUrlResult);
            dispose(nullClientRequest);
            dispose(disposedRequest);
            dispose(disposedFrameRequest);
            dispose(invalidUrlRequest);
            if (disposedFrame != null) disposedFrame.dispose();
        }
    }

    private static CefRequest createRequest(String url) {
        CefRequest request = CefRequest.create();
        assertNotNull(request);
        request.setURL(url);
        request.setMethod("GET");
        request.setFlags(CefRequest.CefUrlRequestFlags.UR_FLAG_SKIP_CACHE | NO_SIMPLE_URL_LOADER_RETRIES);
        return request;
    }

    private static void dispose(CefURLRequest request) {
        if (request == null) return;
        request.cancel();
        request.dispose();
    }

    private static void dispose(CefRequest request) {
        if (request != null) request.dispose();
    }

    private static void assertNoFailure(AtomicReference<Throwable> failure) {
        Throwable throwable = failure.get();
        if (throwable != null) throw new AssertionError("Frame URL request callback failed", throwable);
    }

    private static Throwable mergeFailure(Throwable primaryFailure, Throwable cleanupFailure) {
        if (primaryFailure == null) return cleanupFailure;
        if (primaryFailure != cleanupFailure) primaryFailure.addSuppressed(cleanupFailure);
        return primaryFailure;
    }

    private static void rethrowFailure(Throwable failure) throws Exception {
        if (failure == null) return;
        if (failure instanceof Error) throw(Error) failure;
        if (failure instanceof Exception) throw(Exception) failure;
        throw new AssertionError("Unexpected frame URL request test failure", failure);
    }

    private static void awaitCallbackLatch(CountDownLatch latch, String description) throws Exception {
        if (!latch.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for " + description);
    }

    private static final class CleanupState {
        private Throwable failure_;
        private boolean interrupted_;

        CleanupState(Throwable primaryFailure) {
            failure_ = primaryFailure;
            interrupted_ = causedByInterruption(primaryFailure);
            captureAndClearInterruptFlag();
        }

        void awaitCreatorThread(AtomicReference<Thread> threadReference, CountDownLatch finished, String description, boolean join) {
            Thread thread = threadReference.get();
            if (thread == null) return;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CALLBACK_TIMEOUT_SECONDS);
            awaitLatch(finished, deadline, description);
            if (join) joinThread(thread, System.nanoTime() + TimeUnit.SECONDS.toNanos(CALLBACK_TIMEOUT_SECONDS), description);
        }

        void terminateAndAwait(TestFrame frame) {
            if (frame == null) return;
            runCleanup(frame::terminateTest);
            runCleanup(frame::awaitCompletion);
        }

        void closeServer(LoopbackHttpServer server) {
            if (server != null) runCleanup(server::close);
        }

        void disposeRequestContext(CefRequestContext context) {
            if (context == null) return;
            runCleanup(context::clearSchemeHandlerFactories);
            runCleanup(context::dispose);
        }

        Throwable finish() {
            captureAndClearInterruptFlag();
            if (interrupted_) Thread.currentThread().interrupt();
            return failure_;
        }

        private boolean awaitLatch(CountDownLatch latch, long deadline, String description) {
            while (latch.getCount() != 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    recordFailure(new AssertionError("Timed out waiting for " + description));
                    return false;
                }
                try {
                    if (!latch.await(remaining, TimeUnit.NANOSECONDS)) {
                        recordFailure(new AssertionError("Timed out waiting for " + description));
                        return false;
                    }
                } catch (InterruptedException interrupted) {
                    recordInterruption("Interrupted while waiting for " + description, interrupted);
                }
            }
            return true;
        }

        private void joinThread(Thread thread, long deadline, String description) {
            while (thread.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    recordFailure(new AssertionError("Timed out joining " + description));
                    return;
                }
                long milliseconds = TimeUnit.NANOSECONDS.toMillis(remaining);
                int nanoseconds = (int) (remaining - TimeUnit.MILLISECONDS.toNanos(milliseconds));
                try {
                    thread.join(milliseconds, nanoseconds);
                } catch (InterruptedException interrupted) {
                    recordInterruption("Interrupted while joining " + description, interrupted);
                }
            }
        }

        private void runCleanup(CleanupAction action) {
            captureAndClearInterruptFlag();
            try {
                action.run();
            } catch (Throwable cleanupFailure) {
                recordFailure(cleanupFailure);
            } finally {
                captureAndClearInterruptFlag();
            }
        }

        private void recordInterruption(String message, InterruptedException interrupted) {
            interrupted_ = true;
            recordFailure(new AssertionError(message, interrupted));
        }

        private void recordFailure(Throwable cleanupFailure) {
            if (causedByInterruption(cleanupFailure)) interrupted_ = true;
            failure_ = mergeFailure(failure_, cleanupFailure);
        }

        private void captureAndClearInterruptFlag() {
            if (Thread.interrupted()) interrupted_ = true;
        }

        private static boolean causedByInterruption(Throwable throwable) {
            while (throwable != null) {
                if (throwable instanceof InterruptedException) return true;
                throwable = throwable.getCause();
            }
            return false;
        }

        @FunctionalInterface
        private interface CleanupAction {
            void run() throws Exception;
        }
    }

    private abstract static class BaseClient implements CefURLRequestClient {
        private final AtomicLong nativeRef_ = new AtomicLong();

        @Override
        public void setNativeRef(String identifier, long nativeRef) {
            nativeRef_.set(nativeRef);
        }

        @Override
        public long getNativeRef(String identifier) {
            return nativeRef_.get();
        }

        @Override
        public boolean getAuthCredentials(boolean isProxy, String host, int port, String realm, String scheme, CefAuthCallback callback) {
            return false;
        }
    }

    private static final class InvalidCreationClient extends BaseClient {
        private final AtomicInteger completionCount_ = new AtomicInteger();

        @Override
        public void onRequestComplete(CefURLRequest request) {
            completionCount_.incrementAndGet();
        }

        @Override
        public void onDownloadData(CefURLRequest request, byte[] data, int dataLength) {}
    }

    private static final class AssociatedSchemeFactory implements CefSchemeHandlerFactory {
        private final String expectedUrl_;
        private final byte[] body_;
        private final AtomicReference<Throwable> failure_;
        private final AtomicReference<String> expectedFrameIdentifier_;
        private final AtomicReference<CefBrowser> expectedBrowser_ = new AtomicReference<CefBrowser>();
        private final AtomicInteger calls_ = new AtomicInteger();

        AssociatedSchemeFactory(String expectedUrl, byte[] body, AtomicReference<Throwable> failure, AtomicReference<String> expectedFrameIdentifier) {
            expectedUrl_ = expectedUrl;
            body_ = body.clone();
            failure_ = failure;
            expectedFrameIdentifier_ = expectedFrameIdentifier;
        }

        void setExpectedBrowser(CefBrowser browser) {
            assertTrue(expectedBrowser_.compareAndSet(null, browser));
        }

        @Override
        public CefResourceHandler create(CefBrowser browser, CefFrame frame, String schemeName, CefRequest request) {
            try {
                calls_.incrementAndGet();
                assertSame(expectedBrowser_.get(), browser);
                assertNotNull(frame);
                assertTrue(frame.isMain());
                assertEquals(expectedFrameIdentifier_.get(), frame.getIdentifier());
                assertEquals("http", schemeName);
                assertEquals(expectedUrl_, request.getURL());
            } catch (Throwable throwable) {
                failure_.compareAndSet(null, throwable);
            }
            return new ImmediateResourceHandler(body_);
        }
    }

    private static final class ImmediateResourceHandler implements CefResourceHandler {
        private final byte[] body_;
        private int offset_;

        ImmediateResourceHandler(byte[] body) {
            body_ = body.clone();
        }

        @Override
        public boolean processRequest(CefRequest request, CefCallback callback) {
            // Headers are immediately available, so CEF permits synchronous continuation from
            // ProcessRequest. Keeping the callback on this stack prevents it escaping teardown.
            callback.Continue();
            return true;
        }

        @Override
        public void getResponseHeaders(CefResponse response, IntRef responseLength, StringRef redirectUrl) {
            response.setStatus(200);
            response.setMimeType("text/plain");
            responseLength.set(body_.length);
        }

        @Override
        public boolean readResponse(byte[] dataOut, int bytesToRead, IntRef bytesRead, CefCallback callback) {
            int length = Math.min(bytesToRead, body_.length - offset_);
            if (length == 0) {
                bytesRead.set(0);
                return false;
            }
            System.arraycopy(body_, offset_, dataOut, 0, length);
            offset_ += length;
            bytesRead.set(length);
            return true;
        }

        @Override
        public void cancel() {}
    }

    private static final class RecordingClient extends BaseClient {
        private final CefRequest expectedRequest_;
        private final byte[] expectedBody_;
        private final AtomicReference<Throwable> failure_;
        private final Runnable completion_;
        private final AtomicReference<CefURLRequest> expectedURLRequest_ = new AtomicReference<CefURLRequest>();
        private final ByteArrayOutputStream body_ = new ByteArrayOutputStream();
        private final AtomicInteger completionCount_ = new AtomicInteger();
        private final AtomicInteger responseStatus_ = new AtomicInteger(-1);
        private final AtomicReference<CefURLRequest.Status> requestStatus_ = new AtomicReference<CefURLRequest.Status>();
        private final AtomicReference<ErrorCode> requestError_ = new AtomicReference<ErrorCode>();

        RecordingClient(CefRequest expectedRequest, byte[] expectedBody, AtomicReference<Throwable> failure, Runnable completion) {
            expectedRequest_ = expectedRequest;
            expectedBody_ = expectedBody.clone();
            failure_ = failure;
            completion_ = completion;
        }

        void publishReturnedURLRequest(CefURLRequest request) {
            assertNotNull(request);
            assertSame(request, adoptURLRequest(request));
        }

        @Override
        public void onUploadProgress(CefURLRequest request, long current, long total) {
            verifyIdentity(request);
        }

        @Override
        public void onDownloadProgress(CefURLRequest request, long current, long total) {
            verifyIdentity(request);
        }

        @Override
        public synchronized void onDownloadData(CefURLRequest request, byte[] data, int dataLength) {
            try {
                verifyIdentity(request);
                assertEquals(data.length, dataLength);
                body_.write(data, 0, dataLength);
            } catch (Throwable throwable) {
                failure_.compareAndSet(null, throwable);
            }
        }

        @Override
        public void onRequestComplete(CefURLRequest request) {
            CefResponse response = null;
            try {
                verifyIdentity(request);
                assertSame(expectedRequest_, request.getRequest());
                assertSame(this, request.getClient());
                requestStatus_.set(request.getRequestStatus());
                requestError_.set(request.getRequestError());
                assertFalse(request.responseWasCached());
                response = request.getResponse();
                assertNotNull(response);
                responseStatus_.set(response.getStatus());
                request.cancel();
                completionCount_.incrementAndGet();
            } catch (Throwable throwable) {
                failure_.compareAndSet(null, throwable);
            } finally {
                if (response != null) response.dispose();
                request.dispose();
                completion_.run();
            }
        }

        void assertSuccessful() {
            assertEquals(1, completionCount_.get());
            assertEquals(CefURLRequest.Status.UR_SUCCESS, requestStatus_.get());
            assertEquals(ErrorCode.ERR_NONE, requestError_.get());
            assertEquals(200, responseStatus_.get());
            synchronized (this) {
                assertEquals(new String(expectedBody_, StandardCharsets.UTF_8), body_.toString(StandardCharsets.UTF_8));
            }
        }

        private void verifyIdentity(CefURLRequest request) {
            try {
                assertNotNull(request);
                assertSame(request, adoptURLRequest(request));
            } catch (Throwable throwable) {
                failure_.compareAndSet(null, throwable);
            }
        }

        private CefURLRequest adoptURLRequest(CefURLRequest request) {
            CefURLRequest adopted = expectedURLRequest_.compareAndExchange(null, request);
            return adopted == null ? request : adopted;
        }
    }

    private static final class CancelClient extends BaseClient {
        private final CefRequest expectedRequest_;
        private final AtomicReference<Throwable> failure_;
        private final Runnable completion_;
        private final AtomicReference<CefURLRequest> expectedURLRequest_ = new AtomicReference<CefURLRequest>();
        private final AtomicInteger completionCount_ = new AtomicInteger();
        private final AtomicReference<CefURLRequest.Status> requestStatus_ = new AtomicReference<CefURLRequest.Status>();
        private final AtomicReference<ErrorCode> requestError_ = new AtomicReference<ErrorCode>();

        CancelClient(CefRequest expectedRequest, AtomicReference<Throwable> failure, Runnable completion) {
            expectedRequest_ = expectedRequest;
            failure_ = failure;
            completion_ = completion;
        }

        void setExpectedURLRequest(CefURLRequest request) {
            assertNotNull(request);
            assertTrue(expectedURLRequest_.compareAndSet(null, request));
        }

        @Override
        public void onRequestComplete(CefURLRequest request) {
            try {
                assertSame(expectedURLRequest_.get(), request);
                assertSame(expectedRequest_, request.getRequest());
                assertSame(this, request.getClient());
                requestStatus_.set(request.getRequestStatus());
                requestError_.set(request.getRequestError());
                request.cancel();
                completionCount_.incrementAndGet();
            } catch (Throwable throwable) {
                failure_.compareAndSet(null, throwable);
            } finally {
                request.dispose();
                completion_.run();
            }
        }

        @Override
        public void onDownloadData(CefURLRequest request, byte[] data, int dataLength) {
            try {
                assertSame(expectedURLRequest_.get(), request);
                throw new AssertionError("Canceled request unexpectedly downloaded data");
            } catch (Throwable throwable) {
                failure_.compareAndSet(null, throwable);
            }
        }

        void assertCanceled() {
            assertEquals(1, completionCount_.get());
            assertEquals(CefURLRequest.Status.UR_CANCELED, requestStatus_.get());
            assertEquals(ErrorCode.ERR_ABORTED, requestError_.get());
        }
    }
}
