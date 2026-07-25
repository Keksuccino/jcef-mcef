// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.callback.CefAuthCallback;
import org.cef.callback.CefURLRequestClient;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;
import org.cef.network.CefURLRequest;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@NativeCefTest
class CefURLRequestClientNativeTest {
    private static final long CALLBACK_TIMEOUT_SECONDS = 20;
    private static final byte[] EMPTY_BODY = new byte[0];

    @Test
    void invalidRequestOrClientReturnsNullWithoutBindingTheClient() {
        InvalidCreationClient client = new InvalidCreationClient();
        CefRequest validRequest = CefRequest.create();
        assertNotNull(validRequest);
        try {
            CefURLRequest nullRequest = CefURLRequest.create(null, client);
            CefURLRequest nullClient = CefURLRequest.create(validRequest, null);

            assertNull(nullRequest);
            assertNull(nullClient);
            assertEquals(0, client.nativeRefReads_.get());
            assertEquals(0, client.nativeRefWrites_.get());
            assertEquals(0, client.completionCount_.get());
        } finally {
            validRequest.dispose();
        }
    }

    @Test
    void reusedClientReceivesEachConcurrentRequestIdentityWithoutNativeCaching() throws Exception {
        CountDownLatch requestsReady = new CountDownLatch(2);
        SharedClient client = new SharedClient();
        CefRequest firstRequest = null;
        CefRequest secondRequest = null;
        CefURLRequest firstUrlRequest = null;
        CefURLRequest secondUrlRequest = null;
        try (LoopbackHttpServer server = new LoopbackHttpServer(2, (requestLine, output) -> {
            requestsReady.countDown();
            awaitCallbackLatch(requestsReady, "both concurrent HTTP requests");
            byte[] body = new byte[256 * 1024];
            Arrays.fill(body, (byte) (requestLine.contains("/first") ? 1 : 2));
            writeResponse(output, "200 OK", "Content-Type: application/octet-stream\r\n", body);
        })) {
            firstRequest = createRequest(server.url("/first"));
            secondRequest = createRequest(server.url("/second"));
            firstUrlRequest = CefURLRequest.create(firstRequest, client);
            secondUrlRequest = CefURLRequest.create(secondRequest, client);
            assertNotNull(firstUrlRequest);
            assertNotNull(secondUrlRequest);

            awaitLatch(client.completions_, "both concurrent CefURLRequest completions");
            server.awaitHealthy();
            assertNoFailure(client.failure_, "Concurrent request callback failed");
            assertEquals(2, client.completedRequests_.size());
            Set<CefURLRequest> identities = Collections.newSetFromMap(new IdentityHashMap<CefURLRequest, Boolean>());
            identities.addAll(client.completedRequests_);
            assertEquals(2, identities.size());
            assertTrue(identities.contains(firstUrlRequest));
            assertTrue(identities.contains(secondUrlRequest));
            assertSame(client, firstUrlRequest.getClient());
            assertSame(client, secondUrlRequest.getClient());
            assertEquals(CefURLRequest.Status.UR_SUCCESS, firstUrlRequest.getRequestStatus());
            assertEquals(CefURLRequest.Status.UR_SUCCESS, secondUrlRequest.getRequestStatus());
            assertEquals(0, client.nativeRefReads_.get());
            assertEquals(0, client.nativeRefWrites_.get());
            assertEquals(0L, client.nativeRef_.get());
            assertTrue(client.legacyProgressCalls_.get() > 0);
            assertTrue(client.downloadCallbacks_.get() > 0);
        } finally {
            dispose(firstUrlRequest);
            dispose(secondUrlRequest);
            dispose(firstRequest);
            dispose(secondRequest);
        }
    }

    @Test
    void cancelCompletesWhileBasicAuthCallbackRemainsInFlight() throws Exception {
        AtomicReference<CefURLRequest> requestReference = new AtomicReference<CefURLRequest>();
        CountDownLatch requestAssigned = new CountDownLatch(1);
        BlockingAuthClient client = new BlockingAuthClient(requestReference, requestAssigned);
        ExecutorService cancelExecutor = Executors.newSingleThreadExecutor(daemonThreads("url-request-cancel"));
        Future<?> cancelResult = null;
        CefRequest request = null;
        CefURLRequest urlRequest = null;
        try (LoopbackHttpServer server = challengeServer()) {
            request = createAuthRequest(server.url("/auth-overlap"));
            urlRequest = CefURLRequest.create(request, client);
            assertNotNull(urlRequest);
            requestReference.set(urlRequest);
            requestAssigned.countDown();

            awaitLatch(client.authEntered_, "the basic-auth callback to begin");
            CefURLRequest requestToCancel = urlRequest;
            cancelResult = cancelExecutor.submit(requestToCancel::cancel);
            awaitLatch(client.completion_, "cancellation completion while auth remained blocked");
            assertTrue(client.completedBeforeAuthRelease_.get(), "Completion did not overlap the in-flight auth callback");
            client.releaseAuth_.countDown();
            cancelResult.get(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            awaitLatch(client.authReturned_, "the basic-auth callback to return");
            server.awaitHealthy();
            assertNoFailure(client.failure_, "Auth/cancel overlap callback failed");
            assertEquals(1, client.completionCount_.get());
            assertEquals(CefURLRequest.Status.UR_CANCELED, client.completionStatus_.get());
        } finally {
            client.releaseAuth_.countDown();
            if (cancelResult != null) cancelResult.cancel(true);
            cancelExecutor.shutdownNow();
            dispose(urlRequest);
            dispose(request);
        }
    }

    @Test
    void basicAuthCallbackCanSynchronouslyCancelItsRequest() throws Exception {
        AtomicReference<CefURLRequest> requestReference = new AtomicReference<CefURLRequest>();
        CountDownLatch requestAssigned = new CountDownLatch(1);
        SynchronousAuthCancelClient client = new SynchronousAuthCancelClient(requestReference, requestAssigned);
        CefRequest request = null;
        CefURLRequest urlRequest = null;
        try (LoopbackHttpServer server = challengeServer()) {
            request = createAuthRequest(server.url("/auth-reentrant-cancel"));
            urlRequest = CefURLRequest.create(request, client);
            assertNotNull(urlRequest);
            requestReference.set(urlRequest);
            requestAssigned.countDown();

            awaitLatch(client.authEntered_, "the basic-auth callback to begin");
            awaitLatch(client.completion_, "the synchronous auth cancellation to complete");
            awaitLatch(client.authReturned_, "the synchronous auth cancellation call to return");
            server.awaitHealthy();
            assertNoFailure(client.failure_, "Synchronous auth cancellation callback failed");
            assertEquals(1, client.completionCount_.get());
            assertEquals(CefURLRequest.Status.UR_CANCELED, client.completionStatus_.get());
        } finally {
            dispose(urlRequest);
            dispose(request);
        }
    }

    @Test
    void completionCanReenterTerminalOperationsAndDispose() throws Exception {
        CountDownLatch responseAllowed = new CountDownLatch(1);
        ReentrantCompletionClient client = new ReentrantCompletionClient();
        CefRequest request = null;
        CefURLRequest urlRequest = null;
        try (LoopbackHttpServer server = new LoopbackHttpServer(1, (requestLine, output) -> {
            awaitCallbackLatch(responseAllowed, "the Java request reference before responding");
            writeResponse(output, "200 OK", "Content-Type: text/plain\r\n", "complete".getBytes(StandardCharsets.UTF_8));
        })) {
            request = createRequest(server.url("/reentrant-completion"));
            urlRequest = CefURLRequest.create(request, client);
            assertNotNull(urlRequest);
            client.expectedRequest_.set(urlRequest);
            responseAllowed.countDown();

            awaitLatch(client.completion_, "the reentrant terminal completion callback");
            server.awaitHealthy();
            assertNoFailure(client.failure_, "Reentrant terminal completion callback failed");
            assertEquals(1, client.completionCount_.get());
            assertEquals(CefURLRequest.Status.UR_SUCCESS, client.completionStatus_.get());
            assertEquals(200, client.responseStatus_.get());
        } finally {
            responseAllowed.countDown();
            dispose(urlRequest);
            dispose(request);
        }
    }

    @Test
    void throwingCompletionDoesNotPoisonLaterJniCallbacks() throws Exception {
        CountDownLatch requestsReady = new CountDownLatch(2);
        ThrowingCompletionClient client = new ThrowingCompletionClient();
        CefRequest firstRequest = null;
        CefRequest secondRequest = null;
        CefURLRequest firstUrlRequest = null;
        CefURLRequest secondUrlRequest = null;
        try (LoopbackHttpServer server = new LoopbackHttpServer(2, (requestLine, output) -> {
            requestsReady.countDown();
            awaitCallbackLatch(requestsReady, "both throwing-completion HTTP requests");
            writeResponse(output, "200 OK", "Content-Type: text/plain\r\n", requestLine.getBytes(StandardCharsets.US_ASCII));
        })) {
            firstRequest = createRequest(server.url("/throw-first"));
            secondRequest = createRequest(server.url("/complete-second"));
            firstUrlRequest = CefURLRequest.create(firstRequest, client);
            secondUrlRequest = CefURLRequest.create(secondRequest, client);
            assertNotNull(firstUrlRequest);
            assertNotNull(secondUrlRequest);

            awaitLatch(client.completions_, "both completion callbacks after the first one throws");
            server.awaitHealthy();
            assertEquals(2, client.completionCount_.get());
            assertEquals(2, client.completedRequests_.size());
            assertEquals(2, client.successfulStatuses_.get());
            Set<CefURLRequest> identities = Collections.newSetFromMap(new IdentityHashMap<CefURLRequest, Boolean>());
            identities.addAll(client.completedRequests_);
            assertEquals(2, identities.size());
            assertTrue(identities.contains(firstUrlRequest));
            assertTrue(identities.contains(secondUrlRequest));
        } finally {
            dispose(firstUrlRequest);
            dispose(secondUrlRequest);
            dispose(firstRequest);
            dispose(secondRequest);
        }
    }

    private static LoopbackHttpServer challengeServer() throws IOException {
        return new LoopbackHttpServer(1, (requestLine, output) -> writeResponse(output, "401 Unauthorized", "WWW-Authenticate: Basic realm=\"jcef-test\"\r\n", EMPTY_BODY));
    }

    private static CefRequest createRequest(String url) {
        CefRequest request = CefRequest.create();
        assertNotNull(request);
        request.setURL(url);
        request.setMethod("GET");
        request.setFlags(CefRequest.CefUrlRequestFlags.UR_FLAG_SKIP_CACHE);
        return request;
    }

    private static CefRequest createAuthRequest(String url) {
        CefRequest request = createRequest(url);
        request.setFlags(CefRequest.CefUrlRequestFlags.UR_FLAG_SKIP_CACHE | CefRequest.CefUrlRequestFlags.UR_FLAG_ALLOW_STORED_CREDENTIALS);
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

    private static void awaitLatch(CountDownLatch latch, String description) throws InterruptedException {
        assertTrue(latch.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS), "Timed out waiting for " + description);
    }

    private static void awaitCallbackLatch(CountDownLatch latch, String description) throws Exception {
        if (!latch.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            throw new IOException("Timed out waiting for " + description);
    }

    private static void assertNoFailure(AtomicReference<Throwable> failure, String message) {
        Throwable throwable = failure.get();
        if (throwable != null) throw new AssertionError(message, throwable);
    }

    private static void writeResponse(OutputStream output, String status, String extraHeaders, byte[] body) throws IOException {
        byte[] headers = ("HTTP/1.1 " + status + "\r\n" + extraHeaders + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        output.write(headers);
        output.write(body);
        output.flush();
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
        public void onDownloadData(CefURLRequest request, byte[] data, int dataLength) {}

        @Override
        public boolean getAuthCredentials(boolean isProxy, String host, int port, String realm, String scheme, CefAuthCallback callback) {
            return false;
        }
    }

    private static final class SharedClient extends BaseClient {
        private final AtomicLong nativeRef_ = new AtomicLong();
        private final AtomicInteger nativeRefReads_ = new AtomicInteger();
        private final AtomicInteger nativeRefWrites_ = new AtomicInteger();
        private final AtomicInteger legacyProgressCalls_ = new AtomicInteger();
        private final AtomicInteger downloadCallbacks_ = new AtomicInteger();
        private final AtomicReference<Throwable> failure_ = new AtomicReference<Throwable>();
        private final List<CefURLRequest> completedRequests_ = new CopyOnWriteArrayList<CefURLRequest>();
        private final CountDownLatch completions_ = new CountDownLatch(2);

        @Override
        public void setNativeRef(String identifier, long nativeRef) {
            nativeRefWrites_.incrementAndGet();
            nativeRef_.set(nativeRef);
        }

        @Override
        public long getNativeRef(String identifier) {
            nativeRefReads_.incrementAndGet();
            return nativeRef_.get();
        }

        @Override
        public void onRequestComplete(CefURLRequest request) {
            completedRequests_.add(request);
            completions_.countDown();
        }

        @Override
        public void onUploadProgress(CefURLRequest request, int current, int total) {
            legacyProgressCalls_.incrementAndGet();
        }

        @Override
        public void onDownloadProgress(CefURLRequest request, int current, int total) {
            legacyProgressCalls_.incrementAndGet();
        }

        @Override
        public void onDownloadData(CefURLRequest request, byte[] data, int dataLength) {
            try {
                if (dataLength != data.length)
                    throw new AssertionError("JNI data length did not match the byte array length");
                downloadCallbacks_.incrementAndGet();
            } catch (Throwable throwable) {
                failure_.compareAndSet(null, throwable);
            }
        }
    }

    private static final class BlockingAuthClient extends BaseClient {
        private final AtomicReference<CefURLRequest> requestReference_;
        private final CountDownLatch requestAssigned_;
        private final CountDownLatch authEntered_ = new CountDownLatch(1);
        private final CountDownLatch releaseAuth_ = new CountDownLatch(1);
        private final CountDownLatch authReturned_ = new CountDownLatch(1);
        private final CountDownLatch completion_ = new CountDownLatch(1);
        private final AtomicBoolean completedBeforeAuthRelease_ = new AtomicBoolean();
        private final AtomicInteger completionCount_ = new AtomicInteger();
        private final AtomicReference<CefURLRequest.Status> completionStatus_ = new AtomicReference<CefURLRequest.Status>();
        private final AtomicReference<Throwable> failure_ = new AtomicReference<Throwable>();

        BlockingAuthClient(AtomicReference<CefURLRequest> requestReference, CountDownLatch requestAssigned) {
            requestReference_ = requestReference;
            requestAssigned_ = requestAssigned;
        }

        @Override
        public void onRequestComplete(CefURLRequest request) {
            try {
                completionCount_.incrementAndGet();
                completedBeforeAuthRelease_.set(releaseAuth_.getCount() != 0);
                completionStatus_.set(request.getRequestStatus());
            } catch (Throwable throwable) {
                failure_.compareAndSet(null, throwable);
            } finally {
                completion_.countDown();
            }
        }

        @Override
        public boolean getAuthCredentials(boolean isProxy, String host, int port, String realm, String scheme, CefAuthCallback callback) {
            try {
                awaitCallbackLatch(requestAssigned_, "the Java CefURLRequest assignment");
                if (requestReference_.get() == null)
                    throw new AssertionError("Missing Java CefURLRequest during auth");
                authEntered_.countDown();
                awaitCallbackLatch(releaseAuth_, "auth callback release after cancellation completion");
            } catch (Throwable throwable) {
                failure_.compareAndSet(null, throwable);
            } finally {
                authReturned_.countDown();
            }
            return false;
        }
    }

    private static final class SynchronousAuthCancelClient extends BaseClient {
        private final AtomicReference<CefURLRequest> requestReference_;
        private final CountDownLatch requestAssigned_;
        private final CountDownLatch authEntered_ = new CountDownLatch(1);
        private final CountDownLatch authReturned_ = new CountDownLatch(1);
        private final CountDownLatch completion_ = new CountDownLatch(1);
        private final AtomicInteger completionCount_ = new AtomicInteger();
        private final AtomicReference<CefURLRequest.Status> completionStatus_ = new AtomicReference<CefURLRequest.Status>();
        private final AtomicReference<Throwable> failure_ = new AtomicReference<Throwable>();

        SynchronousAuthCancelClient(AtomicReference<CefURLRequest> requestReference, CountDownLatch requestAssigned) {
            requestReference_ = requestReference;
            requestAssigned_ = requestAssigned;
        }

        @Override
        public void onRequestComplete(CefURLRequest request) {
            CefResponse response = null;
            try {
                completionCount_.incrementAndGet();
                completionStatus_.set(request.getRequestStatus());
                response = request.getResponse();
                request.cancel();
                if (response != null) {
                    response.dispose();
                    response = null;
                }
                request.dispose();
            } catch (Throwable throwable) {
                failure_.compareAndSet(null, throwable);
            } finally {
                if (response != null) response.dispose();
                completion_.countDown();
            }
        }

        @Override
        public boolean getAuthCredentials(boolean isProxy, String host, int port, String realm, String scheme, CefAuthCallback callback) {
            try {
                awaitCallbackLatch(requestAssigned_, "the Java CefURLRequest assignment");
                CefURLRequest request = requestReference_.get();
                if (request == null)
                    throw new AssertionError("Missing Java CefURLRequest during auth");
                authEntered_.countDown();
                request.cancel();
            } catch (Throwable throwable) {
                failure_.compareAndSet(null, throwable);
            } finally {
                authReturned_.countDown();
            }
            return false;
        }
    }

    private static final class ReentrantCompletionClient extends BaseClient {
        private final AtomicReference<CefURLRequest> expectedRequest_ = new AtomicReference<CefURLRequest>();
        private final CountDownLatch completion_ = new CountDownLatch(1);
        private final AtomicInteger completionCount_ = new AtomicInteger();
        private final AtomicReference<CefURLRequest.Status> completionStatus_ = new AtomicReference<CefURLRequest.Status>();
        private final AtomicInteger responseStatus_ = new AtomicInteger(-1);
        private final AtomicReference<Throwable> failure_ = new AtomicReference<Throwable>();

        @Override
        public void onRequestComplete(CefURLRequest request) {
            CefResponse response = null;
            try {
                completionCount_.incrementAndGet();
                if (request != expectedRequest_.get())
                    throw new AssertionError("Completion received the wrong Java CefURLRequest");
                completionStatus_.set(request.getRequestStatus());
                response = request.getResponse();
                if (response == null)
                    throw new AssertionError("Successful request had no terminal response");
                responseStatus_.set(response.getStatus());
                request.cancel();
                response.dispose();
                response = null;
                request.dispose();
            } catch (Throwable throwable) {
                failure_.compareAndSet(null, throwable);
            } finally {
                if (response != null) response.dispose();
                completion_.countDown();
            }
        }
    }

    private static final class InvalidCreationClient extends BaseClient {
        private final AtomicInteger nativeRefReads_ = new AtomicInteger();
        private final AtomicInteger nativeRefWrites_ = new AtomicInteger();
        private final AtomicInteger completionCount_ = new AtomicInteger();

        @Override
        public void setNativeRef(String identifier, long nativeRef) {
            nativeRefWrites_.incrementAndGet();
        }

        @Override
        public long getNativeRef(String identifier) {
            nativeRefReads_.incrementAndGet();
            return 0;
        }

        @Override
        public void onRequestComplete(CefURLRequest request) {
            completionCount_.incrementAndGet();
        }
    }

    private static final class ThrowingCompletionClient extends BaseClient {
        private final AtomicInteger completionCount_ = new AtomicInteger();
        private final AtomicInteger successfulStatuses_ = new AtomicInteger();
        private final List<CefURLRequest> completedRequests_ = new CopyOnWriteArrayList<CefURLRequest>();
        private final CountDownLatch completions_ = new CountDownLatch(2);

        @Override
        public void onRequestComplete(CefURLRequest request) {
            int completion = completionCount_.incrementAndGet();
            completedRequests_.add(request);
            if (request.getRequestStatus() == CefURLRequest.Status.UR_SUCCESS)
                successfulStatuses_.incrementAndGet();
            completions_.countDown();
            if (completion == 1) throw new IllegalStateException("Intentional completion exception for JNI cleanup regression coverage");
        }
    }

    @FunctionalInterface
    private interface ResponseHandler {
        void handle(String requestLine, OutputStream output) throws Exception;
    }

    private static final class LoopbackHttpServer implements AutoCloseable {
        private final int requestCount_;
        private final ResponseHandler handler_;
        private final ServerSocket serverSocket_ = new ServerSocket();
        private final CountDownLatch responsesFinished_;
        private final AtomicReference<Throwable> failure_ = new AtomicReference<Throwable>();
        private final ExecutorService acceptor_ = Executors.newSingleThreadExecutor(daemonThreads("url-request-accept"));
        private final ExecutorService connections_;

        LoopbackHttpServer(int requestCount, ResponseHandler handler) throws IOException {
            requestCount_ = requestCount;
            handler_ = handler;
            responsesFinished_ = new CountDownLatch(requestCount);
            connections_ = Executors.newFixedThreadPool(requestCount, daemonThreads("url-request-connection"));
            serverSocket_.setReuseAddress(true);
            serverSocket_.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            acceptor_.execute(this::acceptRequests);
        }

        String url(String path) {
            return "http://127.0.0.1:" + serverSocket_.getLocalPort() + path;
        }

        void awaitHealthy() throws Exception {
            awaitLatch(responsesFinished_, "the loopback HTTP responses");
            assertNoFailure(failure_, "Loopback HTTP server failed");
        }

        private void acceptRequests() {
            try {
                for (int index = 0; index < requestCount_; index++) {
                    Socket socket = serverSocket_.accept();
                    connections_.execute(() -> handleRequest(socket));
                }
            } catch (Throwable throwable) {
                if (!serverSocket_.isClosed()) {
                    failure_.compareAndSet(null, throwable);
                    while (responsesFinished_.getCount() != 0) responsesFinished_.countDown();
                }
            }
        }

        private void handleRequest(Socket socket) {
            try (Socket connection = socket; BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII))) {
                connection.setSoTimeout((int) TimeUnit.SECONDS.toMillis(CALLBACK_TIMEOUT_SECONDS));
                String requestLine = reader.readLine();
                if (requestLine == null || !requestLine.startsWith("GET /"))
                    throw new IOException("Unexpected request line: " + requestLine);
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                }
                handler_.handle(requestLine, connection.getOutputStream());
            } catch (Throwable throwable) {
                failure_.compareAndSet(null, throwable);
            } finally {
                responsesFinished_.countDown();
            }
        }

        @Override
        public void close() {
            try {
                serverSocket_.close();
            } catch (IOException ignored) {
            }
            acceptor_.shutdownNow();
            connections_.shutdownNow();
        }
    }

    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
