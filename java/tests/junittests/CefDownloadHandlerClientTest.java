// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefDownloadHandler;
import org.cef.handler.CefDownloadHandlerAdapter;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@NativeCefTest
class CefDownloadHandlerClientTest {
    private static final String CAN_DOWNLOAD_DESCRIPTOR =
            "(Lorg/cef/browser/CefBrowser;Ljava/lang/String;Ljava/lang/String;)Z";

    @Test
    void clientDeclaresAnExactThreadVisibleCanDownloadRelay() throws Exception {
        Method interfaceMethod = CefDownloadHandler.class.getMethod(
                "canDownload", CefBrowser.class, String.class, String.class);
        Method clientMethod = CefClient.class.getDeclaredMethod(
                "canDownload", CefBrowser.class, String.class, String.class);
        Field delegate = CefClient.class.getDeclaredField("downloadHandler_");

        assertTrue(interfaceMethod.isDefault());
        assertTrue(Modifier.isPublic(clientMethod.getModifiers()));
        assertFalse(Modifier.isAbstract(clientMethod.getModifiers()));
        assertEquals(boolean.class, clientMethod.getReturnType());
        assertEquals(CAN_DOWNLOAD_DESCRIPTOR,
                MethodType
                        .methodType(clientMethod.getReturnType(), clientMethod.getParameterTypes())
                        .toMethodDescriptorString());
        assertEquals(AtomicReference.class, delegate.getType());
        assertTrue(Modifier.isFinal(delegate.getModifiers()));

        String clientSource = readSource("java", "org", "cef", "CefClient.java");
        String stableGetter =
                sourceBetween(clientSource, "protected CefDownloadHandler getDownloadHandler()",
                        "protected CefDragHandler getDragHandler()");
        assertTrue(stableGetter.contains("return this;"));
        String downloadRelay =
                sourceBetween(clientSource, "// CefDownloadHandler", "// CefDragHandler");
        assertTrue(downloadRelay.contains("downloadHandler_.compareAndSet(null, handler)"));
        assertTrue(downloadRelay.contains("downloadHandler_.set(null)"));
        assertEquals(4,
                occurrenceCount(
                        downloadRelay, "CefDownloadHandler handler = downloadHandler_.get();"));
    }

    @Test
    void relayPreservesDecisionsArgumentsAndRegistrationLifecycle() {
        CefClient client = CefApp.getInstance().createClient();
        CefBrowser browser = (CefBrowser) Proxy.newProxyInstance(CefBrowser.class.getClassLoader(),
                new Class<?>[] {CefBrowser.class}, (proxy, method, arguments) -> null);
        RecordingDownloadHandler rejectingHandler = new RecordingDownloadHandler(false);
        RecordingDownloadHandler ignoredHandler = new RecordingDownloadHandler(true);
        String url = "https://example.test/post-download";
        String requestMethod = "POST";

        try {
            assertTrue(client.canDownload(browser, url, requestMethod));
            assertSame(client, client.addDownloadHandler(rejectingHandler));
            assertSame(client, client.addDownloadHandler(ignoredHandler));
            assertFalse(client.canDownload(browser, url, requestMethod));
            assertEquals(1, rejectingHandler.calls_.get());
            assertEquals(0, ignoredHandler.calls_.get());
            assertSame(browser, rejectingHandler.browser_);
            assertSame(url, rejectingHandler.url_);
            assertSame(requestMethod, rejectingHandler.requestMethod_);

            assertTrue(client.canDownload(null, url, requestMethod));
            assertEquals(1, rejectingHandler.calls_.get());

            client.removeDownloadHandler();
            assertTrue(client.canDownload(browser, url, requestMethod));
            assertEquals(1, rejectingHandler.calls_.get());
            assertEquals(0, ignoredHandler.calls_.get());
            client.addDownloadHandler(ignoredHandler);
            assertTrue(client.canDownload(browser, url, requestMethod));
            assertEquals(1, ignoredHandler.calls_.get());
        } finally {
            client.dispose();
        }
    }

    @Test
    void nativeBridgeUsesTheExactRelaySignatureAndDefaultAllowFallbacks() throws Exception {
        String clientHandler = readSource("native", "client_handler.cpp");
        String nativeGetter = sourceBetween(clientHandler,
                "CefRefPtr<CefDownloadHandler> ClientHandler::GetDownloadHandler()",
                "CefRefPtr<CefDragHandler> ClientHandler::GetDragHandler()");
        assertTrue(
                nativeGetter.contains("return GetHandler<DownloadHandler>(\"DownloadHandler\");"));

        String implementation = readSource("native", "download_handler.cpp");
        String callback = sourceBetween(implementation, "bool DownloadHandler::CanDownload(",
                "bool DownloadHandler::OnBeforeDownload(");
        assertTrue(callback.contains("if (!env)\n    return true;"));
        assertTrue(callback.contains("jboolean jresult = JNI_TRUE;"));
        assertTrue(callback.contains("\"canDownload\""));
        assertTrue(callback.contains(
                "\"(Lorg/cef/browser/CefBrowser;Ljava/lang/String;Ljava/lang/String;)Z\""));
        assertTrue(callback.contains("jbrowser.get(), jurl.get(), jrequest_method.get()"));
        assertTrue(callback.contains("return jresult != JNI_FALSE;"));
    }

    private static String readSource(String... pathElements) throws Exception {
        Path sourcePath = Path.of(System.getProperty("user.dir"), pathElements);
        assertTrue(Files.isRegularFile(sourcePath),
                "Run source contract tests from the repository root: " + sourcePath);
        return Files.readString(sourcePath).replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String sourceBetween(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0, () -> "Missing source marker: " + startMarker);
        assertTrue(end > start, () -> "Missing source boundary: " + endMarker);
        return source.substring(start, end);
    }

    private static int occurrenceCount(String source, String marker) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(marker, index)) >= 0) {
            count++;
            index += marker.length();
        }
        return count;
    }

    private static final class RecordingDownloadHandler extends CefDownloadHandlerAdapter {
        private final boolean decision_;
        private final AtomicInteger calls_ = new AtomicInteger();
        private CefBrowser browser_;
        private String url_;
        private String requestMethod_;

        private RecordingDownloadHandler(boolean decision) {
            decision_ = decision;
        }

        @Override
        public boolean canDownload(CefBrowser browser, String url, String requestMethod) {
            calls_.incrementAndGet();
            browser_ = browser;
            url_ = url;
            requestMethod_ = requestMethod;
            return decision_;
        }
    }
}
