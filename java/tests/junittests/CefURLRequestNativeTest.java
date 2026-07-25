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
import org.cef.handler.CefResourceHandler;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;
import org.cef.network.CefURLRequest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@NativeCefTest
class CefURLRequestNativeTest {
    private static final long CALLBACK_TIMEOUT_SECONDS = 20;
    // CEF 151 couples its 5xx and network-change SimpleURLLoader retries: omitting this flag
    // enables both. Disable those configured retries so each Java request maps to one server GET.
    private static final int NO_SIMPLE_URL_LOADER_RETRIES = CefRequest.CefUrlRequestFlags.UR_FLAG_NO_RETRY_ON_5XX;

    @Test
    void creationSuccessReturnsTheSameWrapperAfterReentrantTokenDisposal() throws Exception {
        Class<?> nativeClass = Class.forName("org.cef.network.CefURLRequest_N");
        Constructor<?> constructor = nativeClass.getDeclaredConstructor(CefRequest.class, CefURLRequestClient.class);
        constructor.setAccessible(true);
        Object wrapper = constructor.newInstance(null, null);
        Method race = nativeClass.getDeclaredMethod("N_RunDisposedCreationRaceForTesting", nativeClass);
        race.setAccessible(true);
        assertTrue((Boolean) race.invoke(null, wrapper));

        Method completeCreation = nativeClass.getDeclaredMethod("completeCreation", nativeClass);
        completeCreation.setAccessible(true);
        assertSame(wrapper, completeCreation.invoke(null, wrapper));
        Method getNativeRef = nativeClass.getDeclaredMethod("getNativeRef", String.class);
        getNativeRef.setAccessible(true);
        assertEquals(0L, getNativeRef.invoke(wrapper, "CefURLRequest"));
    }

    @Test
    void tokenRegistryRetainsConcurrentQueryAndCancelLookupsAcrossDisposal() throws Exception {
        assertTrue(invokeNativeBooleanForTesting("N_RunTokenRegistryConcurrencyForTesting"));
    }

    @Test
    void acceptedPendingDispatchIsSafelyAbandonedBeforeLateExecution() throws Exception {
        assertTrue(invokeNativeBooleanForTesting("N_RunPendingDispatchAbandonmentForTesting"));
    }

    @Test
    void lifecycleQuiescesRetainedAccessAndNeverReusesTokensAcrossReopen() throws Exception {
        Class<?> nativeClass = Class.forName("org.cef.network.CefURLRequest_N");
        Constructor<?> constructor = nativeClass.getDeclaredConstructor(CefRequest.class, CefURLRequestClient.class);
        constructor.setAccessible(true);
        Object wrapper = constructor.newInstance(null, null);
        Method lifecycle = nativeClass.getDeclaredMethod("N_RunURLRequestLifecycleForTesting", nativeClass);
        lifecycle.setAccessible(true);
        assertTrue((Boolean) lifecycle.invoke(null, wrapper));

        Method getNativeRef = nativeClass.getDeclaredMethod("getNativeRef", String.class);
        getNativeRef.setAccessible(true);
        assertEquals(0L, getNativeRef.invoke(wrapper, "CefURLRequest"));
    }

    @Test
    void invalidRequestOrClientReturnsNullWithoutBindingTheClient() {
        InvalidCreationClient client = new InvalidCreationClient();
        CefRequest validRequest = CefRequest.create();
        CefRequestContext context = CefRequestContext.createContext(new CefRequestContextSettings(), null);
        try {
            assertNotNull(validRequest);
            assertNotNull(context);
            CefURLRequest nullRequest = CefURLRequest.create(null, client);
            CefURLRequest nullClient = CefURLRequest.create(validRequest, null);
            CefURLRequest explicitNullRequest = CefURLRequest.create(null, client, context);
            CefURLRequest explicitNullClient = CefURLRequest.create(validRequest, null, context);
            context.dispose();

            assertNull(nullRequest);
            assertNull(nullClient);
            assertNull(explicitNullRequest);
            assertNull(explicitNullClient);
            assertThrows(IllegalStateException.class, () -> CefURLRequest.create(validRequest, client, context));
            assertFalse(validRequest.isReadOnly());
            assertEquals(0, client.nativeRefReads_.get());
            assertEquals(0, client.nativeRefWrites_.get());
            assertEquals(0, client.completionCount_.get());
        } finally {
            if (context != null) context.dispose();
            dispose(validRequest);
        }
    }

    @Test
    void legacyNullAndExplicitGlobalContextCreationPreserveJavaIdentity() throws Exception {
        SnapshotClient legacyClient = new SnapshotClient();
        SnapshotClient nullContextClient = new SnapshotClient();
        SnapshotClient globalContextClient = new SnapshotClient();
        CefRequest legacyRequest = null;
        CefRequest nullContextRequest = null;
        CefRequest globalContextRequest = null;
        CefURLRequest legacyUrlRequest = null;
        CefURLRequest nullContextUrlRequest = null;
        CefURLRequest globalContextUrlRequest = null;
        CefRequestContext globalContext = CefRequestContext.getGlobalContext();
        assertNotNull(globalContext);
        try (LoopbackHttpServer server = new LoopbackHttpServer(3, (requestLine, output) -> LoopbackHttpServer.writeResponse(output, "200 OK", "Content-Type: text/plain\r\n", requestLine.getBytes(StandardCharsets.US_ASCII)))) {
            legacyRequest = createRequest(server.url("/legacy"));
            nullContextRequest = createRequest(server.url("/null-context"));
            globalContextRequest = createRequest(server.url("/global-context"));
            legacyUrlRequest = CefURLRequest.create(legacyRequest, legacyClient);
            nullContextUrlRequest = CefURLRequest.create(nullContextRequest, nullContextClient, null);
            globalContextUrlRequest = CefURLRequest.create(globalContextRequest, globalContextClient, globalContext);
            assertNotNull(legacyUrlRequest);
            assertNotNull(nullContextUrlRequest);
            assertNotNull(globalContextUrlRequest);

            legacyClient.awaitCompletion("legacy two-argument request");
            nullContextClient.awaitCompletion("null-context request");
            globalContextClient.awaitCompletion("explicit global-context request");
            server.awaitHealthy();
            legacyClient.assertSuccessful(legacyUrlRequest, legacyRequest, false);
            nullContextClient.assertSuccessful(nullContextUrlRequest, nullContextRequest, false);
            globalContextClient.assertSuccessful(globalContextUrlRequest, globalContextRequest, false);
        } finally {
            dispose(legacyUrlRequest);
            dispose(nullContextUrlRequest);
            dispose(globalContextUrlRequest);
            dispose(legacyRequest);
            dispose(nullContextRequest);
            dispose(globalContextRequest);
        }
    }

    @Test
    void explicitContextIsRetainedAfterItsJavaWrapperIsDisposed() throws Exception {
        CountDownLatch responseAllowed = new CountDownLatch(1);
        SnapshotClient client = new SnapshotClient();
        CefRequestContext context = CefRequestContext.createContext(new CefRequestContextSettings(), null);
        CefRequest request = null;
        CefURLRequest urlRequest = null;
        LoopbackHttpServer.ResponseHandler handler = (requestLine, output) -> {
            awaitCallbackLatch(responseAllowed, "the explicit context to be disposed");
            LoopbackHttpServer.writeResponse(output, "200 OK", "Content-Type: text/plain\r\n", "retained-context".getBytes(StandardCharsets.UTF_8));
        };
        assertNotNull(context);
        try (LoopbackHttpServer server = new LoopbackHttpServer(1, handler)) {
            request = createRequest(server.url("/retained-context"));
            urlRequest = CefURLRequest.create(request, client, context);
            assertNotNull(urlRequest);
            context.dispose();
            responseAllowed.countDown();

            client.awaitCompletion("request retained after explicit context disposal");
            server.awaitHealthy();
            client.assertSuccessful(urlRequest, request, false);
            assertEquals("retained-context", client.body());
        } finally {
            responseAllowed.countDown();
            context.dispose();
            dispose(urlRequest);
            dispose(request);
        }
    }

    @Test
    void explicitContextSelectsOnlyItsOwnSchemeHandlerFactory() throws Exception {
        byte[] interceptedBody = "context-factory".getBytes(StandardCharsets.UTF_8);
        byte[] networkBody = "loopback-network".getBytes(StandardCharsets.UTF_8);
        CefRequestContext interceptedContext = CefRequestContext.createContext(new CefRequestContextSettings(), null);
        CefRequestContext isolatedContext = CefRequestContext.createContext(new CefRequestContextSettings(), null);
        SnapshotClient interceptedClient = new SnapshotClient();
        SnapshotClient isolatedClient = new SnapshotClient();
        RecordingSchemeHandlerFactory factory = new RecordingSchemeHandlerFactory(interceptedBody);
        CefRequest interceptedRequest = null;
        CefRequest isolatedRequest = null;
        CefURLRequest interceptedUrlRequest = null;
        CefURLRequest isolatedUrlRequest = null;
        try (LoopbackHttpServer server = new LoopbackHttpServer(1, (requestLine, output) -> LoopbackHttpServer.writeResponse(output, "200 OK", "Content-Type: text/plain\r\n", networkBody))) {
            assertNotNull(interceptedContext);
            assertNotNull(isolatedContext);
            assertTrue(interceptedContext.registerSchemeHandlerFactory("http", "127.0.0.1", factory));
            String url = server.url("/context-selection");
            interceptedRequest = createRequest(url);
            interceptedUrlRequest = CefURLRequest.create(interceptedRequest, interceptedClient, interceptedContext);
            assertNotNull(interceptedUrlRequest);
            interceptedClient.awaitCompletion("context-specific scheme-handler request");
            interceptedClient.assertSuccessful(interceptedUrlRequest, interceptedRequest, false);
            assertEquals("context-factory", interceptedClient.body());
            assertNoFailure(factory.failure_, "Context scheme-handler factory callback failed");
            assertEquals(1, factory.calls_.get());
            assertEquals(0, server.receivedRequestCount());

            isolatedRequest = createRequest(url);
            isolatedUrlRequest = CefURLRequest.create(isolatedRequest, isolatedClient, isolatedContext);
            assertNotNull(isolatedUrlRequest);
            isolatedClient.awaitCompletion("isolated-context network request");
            server.awaitReceivedRequestCount(1);
            server.assertHealthy();
            isolatedClient.assertSuccessful(isolatedUrlRequest, isolatedRequest, false);
            assertEquals("loopback-network", isolatedClient.body());
            assertEquals(1, factory.calls_.get());
        } finally {
            if (interceptedContext != null) {
                interceptedContext.clearSchemeHandlerFactories();
                interceptedContext.dispose();
            }
            if (isolatedContext != null) isolatedContext.dispose();
            dispose(interceptedUrlRequest);
            dispose(isolatedUrlRequest);
            dispose(interceptedRequest);
            dispose(isolatedRequest);
        }
    }

    @Test
    void responseWasCachedIsExactAtCompletionAndCachesAreContextIsolated() throws Exception {
        byte[] body = "cacheable-response".getBytes(StandardCharsets.UTF_8);
        CefRequestContext cachedContext = CefRequestContext.createContext(new CefRequestContextSettings(), null);
        CefRequestContext isolatedContext = CefRequestContext.createContext(new CefRequestContextSettings(), null);
        CefRequest firstRequest = null;
        CefRequest secondRequest = null;
        CefRequest isolatedRequest = null;
        CefURLRequest firstUrlRequest = null;
        CefURLRequest secondUrlRequest = null;
        CefURLRequest isolatedUrlRequest = null;
        try (LoopbackHttpServer server = new LoopbackHttpServer(2, (requestLine, output) -> LoopbackHttpServer.writeResponse(output, "200 OK", "Cache-Control: public, max-age=3600\r\nContent-Type: text/plain\r\n", body))) {
            assertNotNull(cachedContext);
            assertNotNull(isolatedContext);
            String url = server.url("/context-cache");
            SnapshotClient firstClient = new SnapshotClient();
            firstRequest = createCacheableRequest(url);
            firstUrlRequest = CefURLRequest.create(firstRequest, firstClient, cachedContext);
            assertNotNull(firstUrlRequest);
            firstClient.awaitCompletion("first context cache miss");
            server.awaitReceivedRequestCount(1);
            server.assertHealthy();
            firstClient.assertSuccessful(firstUrlRequest, firstRequest, false);

            SnapshotClient secondClient = new SnapshotClient();
            secondRequest = createCacheableRequest(url);
            secondUrlRequest = CefURLRequest.create(secondRequest, secondClient, cachedContext);
            assertNotNull(secondUrlRequest);
            secondClient.awaitCompletion("second same-context cache hit");
            secondClient.assertSuccessful(secondUrlRequest, secondRequest, true);
            assertEquals(1, server.receivedRequestCount(), "The same-context cache hit unexpectedly reached the server");

            SnapshotClient isolatedClient = new SnapshotClient();
            isolatedRequest = createCacheableRequest(url);
            isolatedUrlRequest = CefURLRequest.create(isolatedRequest, isolatedClient, isolatedContext);
            assertNotNull(isolatedUrlRequest);
            isolatedClient.awaitCompletion("isolated-context cache miss");
            server.awaitReceivedRequestCount(2);
            server.assertHealthy();
            isolatedClient.assertSuccessful(isolatedUrlRequest, isolatedRequest, false);
            assertEquals(body.length, firstClient.dataLength());
            assertEquals(body.length, secondClient.dataLength());
            assertEquals(body.length, isolatedClient.dataLength());
        } finally {
            if (cachedContext != null) cachedContext.dispose();
            if (isolatedContext != null) isolatedContext.dispose();
            dispose(firstUrlRequest);
            dispose(secondUrlRequest);
            dispose(isolatedUrlRequest);
            dispose(firstRequest);
            dispose(secondRequest);
            dispose(isolatedRequest);
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

    private static boolean invokeNativeBooleanForTesting(String methodName) throws Exception {
        Class<?> nativeClass = Class.forName("org.cef.network.CefURLRequest_N");
        Method method = nativeClass.getDeclaredMethod(methodName);
        method.setAccessible(true);
        return (Boolean) method.invoke(null);
    }

    private static CefRequest createCacheableRequest(String url) {
        CefRequest request = CefRequest.create();
        assertNotNull(request);
        request.setURL(url);
        request.setMethod("GET");
        // Intentionally leave the cache enabled for the behavior under test.
        request.setFlags(NO_SIMPLE_URL_LOADER_RETRIES);
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

    private static final class SnapshotClient extends BaseClient {
        private final ByteArrayOutputStream body_ = new ByteArrayOutputStream();
        private final CountDownLatch completion_ = new CountDownLatch(1);
        private final AtomicReference<CefURLRequest> completedRequest_ = new AtomicReference<CefURLRequest>();
        private final AtomicReference<CefURLRequest.Status> status_ = new AtomicReference<CefURLRequest.Status>();
        private final AtomicBoolean responseWasCached_ = new AtomicBoolean();
        private final AtomicInteger responseStatus_ = new AtomicInteger(-1);
        private final AtomicReference<Throwable> failure_ = new AtomicReference<Throwable>();

        @Override
        public synchronized void onDownloadData(CefURLRequest request, byte[] data, int dataLength) {
            try {
                if (dataLength != data.length) throw new AssertionError("JNI data length did not match the byte array length");
                body_.write(data, 0, dataLength);
            } catch (Throwable throwable) {
                failure_.compareAndSet(null, throwable);
            }
        }

        @Override
        public void onRequestComplete(CefURLRequest request) {
            CefResponse response = null;
            try {
                completedRequest_.set(request);
                status_.set(request.getRequestStatus());
                responseWasCached_.set(request.responseWasCached());
                response = request.getResponse();
                if (response == null) throw new AssertionError("Successful request had no terminal response");
                responseStatus_.set(response.getStatus());
            } catch (Throwable throwable) {
                failure_.compareAndSet(null, throwable);
            } finally {
                if (response != null) response.dispose();
                completion_.countDown();
            }
        }

        void awaitCompletion(String description) throws InterruptedException {
            awaitLatch(completion_, description);
            assertNoFailure(failure_, "URL request completion callback failed");
        }

        void assertSuccessful(CefURLRequest expectedUrlRequest, CefRequest expectedRequest, boolean expectedCached) {
            assertSame(expectedUrlRequest, completedRequest_.get());
            assertSame(expectedRequest, expectedUrlRequest.getRequest());
            assertSame(this, expectedUrlRequest.getClient());
            assertEquals(CefURLRequest.Status.UR_SUCCESS, status_.get());
            assertEquals(200, responseStatus_.get());
            assertEquals(expectedCached, responseWasCached_.get());
            assertEquals(expectedCached, expectedUrlRequest.responseWasCached());
        }

        synchronized String body() {
            return body_.toString(StandardCharsets.UTF_8);
        }

        synchronized int dataLength() {
            return body_.size();
        }
    }

    private static final class FixedResourceHandler implements CefResourceHandler {
        private final byte[] body_;
        private int offset_;

        FixedResourceHandler(byte[] body) {
            body_ = body.clone();
        }

        @Override
        public boolean processRequest(CefRequest request, CefCallback callback) {
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

    private static final class RecordingSchemeHandlerFactory implements CefSchemeHandlerFactory {
        private final byte[] body_;
        private final AtomicInteger calls_ = new AtomicInteger();
        private final AtomicReference<Throwable> failure_ = new AtomicReference<Throwable>();

        RecordingSchemeHandlerFactory(byte[] body) {
            body_ = body.clone();
        }

        @Override
        public CefResourceHandler create(CefBrowser browser, CefFrame frame, String schemeName, CefRequest request) {
            try {
                calls_.incrementAndGet();
                assertNull(browser);
                assertNull(frame);
                assertEquals("http", schemeName);
            } catch (Throwable throwable) {
                failure_.compareAndSet(null, throwable);
            }
            return new FixedResourceHandler(body_);
        }
    }
}
