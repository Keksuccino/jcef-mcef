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
import org.cef.callback.CefFileDialogCallback;
import org.cef.handler.CefDialogHandler;
import org.cef.handler.CefDialogHandler.FileDialogMode;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

@NativeCefTest
class CefDialogHandlerCompatibilityTest {
    private static final String LEGACY_DESCRIPTOR =
            "(Lorg/cef/browser/CefBrowser;Lorg/cef/handler/CefDialogHandler$FileDialogMode;Ljava/lang/String;Ljava/lang/String;Ljava/util/Vector;Lorg/cef/callback/CefFileDialogCallback;)Z";
    private static final String EXTENDED_DESCRIPTOR =
            "(Lorg/cef/browser/CefBrowser;Lorg/cef/handler/CefDialogHandler$FileDialogMode;Ljava/lang/String;Ljava/lang/String;Ljava/util/Vector;Ljava/util/Vector;Ljava/util/Vector;Lorg/cef/callback/CefFileDialogCallback;)Z";

    @Test
    void bothHandlerGenerationsRemainConcreteAndBinaryAddressable() throws Exception {
        Method legacy = CefDialogHandler.class.getMethod("onFileDialog", CefBrowser.class,
                FileDialogMode.class, String.class, String.class, Vector.class,
                CefFileDialogCallback.class);
        Method extended = CefDialogHandler.class.getMethod("onFileDialog", CefBrowser.class,
                FileDialogMode.class, String.class, String.class, Vector.class, Vector.class,
                Vector.class, CefFileDialogCallback.class);

        assertTrue(legacy.isDefault());
        assertTrue(legacy.isAnnotationPresent(Deprecated.class));
        assertFalse(Modifier.isAbstract(legacy.getModifiers()));
        assertEquals(LEGACY_DESCRIPTOR,
                MethodType.methodType(legacy.getReturnType(), legacy.getParameterTypes())
                        .toMethodDescriptorString());
        assertTrue(extended.isDefault());
        assertFalse(extended.isAnnotationPresent(Deprecated.class));
        assertFalse(Modifier.isAbstract(extended.getModifiers()));
        assertEquals(EXTENDED_DESCRIPTOR,
                MethodType.methodType(extended.getReturnType(), extended.getParameterTypes())
                        .toMethodDescriptorString());
    }

    @Test
    void extendedDispatchFallsBackToLegacyOverrideExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        CefBrowser browser = browserProxy();
        Vector<String> filters = vector("image/*", ".txt");
        Vector<String> extensions = vector(".png;.jpg", ".txt");
        Vector<String> descriptions = vector("Image Files", "Text Files");
        CefFileDialogCallback callback = callback();
        CefDialogHandler handler = new CefDialogHandler() {
            @Override
            @Deprecated
            public boolean onFileDialog(CefBrowser receivedBrowser, FileDialogMode mode,
                    String title, String defaultFilePath, Vector<String> receivedFilters,
                    CefFileDialogCallback receivedCallback) {
                calls.incrementAndGet();
                assertSame(browser, receivedBrowser);
                assertEquals(FileDialogMode.FILE_DIALOG_OPEN, mode);
                assertEquals("Open file", title);
                assertEquals("/initial/file.txt", defaultFilePath);
                assertSame(filters, receivedFilters);
                assertSame(callback, receivedCallback);
                return true;
            }
        };

        assertTrue(handler.onFileDialog(browser, FileDialogMode.FILE_DIALOG_OPEN, "Open file",
                "/initial/file.txt", filters, extensions, descriptions, callback));
        assertEquals(1, calls.get());
    }

    @Test
    void modernOverrideReceivesAllParallelVectorsUnchanged() {
        AtomicInteger calls = new AtomicInteger();
        CefBrowser browser = browserProxy();
        Vector<String> filters = vector("image/*", "application/octet-stream");
        Vector<String> extensions = vector(".png;.jpg", "");
        Vector<String> descriptions = vector("Image Files", "");
        CefFileDialogCallback callback = callback();
        CefDialogHandler handler = new CefDialogHandler() {
            @Override
            public boolean onFileDialog(CefBrowser receivedBrowser, FileDialogMode mode,
                    String title, String defaultFilePath, Vector<String> receivedFilters,
                    Vector<String> receivedExtensions, Vector<String> receivedDescriptions,
                    CefFileDialogCallback receivedCallback) {
                calls.incrementAndGet();
                assertSame(browser, receivedBrowser);
                assertSame(filters, receivedFilters);
                assertSame(extensions, receivedExtensions);
                assertSame(descriptions, receivedDescriptions);
                assertSame(callback, receivedCallback);
                return true;
            }
        };

        assertTrue(handler.onFileDialog(browser, FileDialogMode.FILE_DIALOG_SAVE, "Save file",
                "/initial/file.bin", filters, extensions, descriptions, callback));
        assertEquals(1, calls.get());
    }

    @Test
    void noOpAndDualOverrideDispatchDoNotRecurse() {
        CefDialogHandler noOp = new CefDialogHandler() {};
        assertFalse(noOp.onFileDialog(
                null, FileDialogMode.FILE_DIALOG_OPEN, "", "", new Vector<String>(), callback()));
        assertFalse(noOp.onFileDialog(null, FileDialogMode.FILE_DIALOG_OPEN, "", "",
                new Vector<String>(), new Vector<String>(), new Vector<String>(), callback()));

        AtomicInteger legacyCalls = new AtomicInteger();
        AtomicInteger extendedCalls = new AtomicInteger();
        CefDialogHandler dual = new CefDialogHandler() {
            @Override
            @Deprecated
            public boolean onFileDialog(CefBrowser browser, FileDialogMode mode, String title,
                    String defaultFilePath, Vector<String> acceptFilters,
                    CefFileDialogCallback callback) {
                legacyCalls.incrementAndGet();
                return false;
            }

            @Override
            public boolean onFileDialog(CefBrowser browser, FileDialogMode mode, String title,
                    String defaultFilePath, Vector<String> acceptFilters,
                    Vector<String> acceptExtensions, Vector<String> acceptDescriptions,
                    CefFileDialogCallback callback) {
                extendedCalls.incrementAndGet();
                return true;
            }
        };

        assertTrue(dual.onFileDialog(null, FileDialogMode.FILE_DIALOG_OPEN_FOLDER, "", "",
                new Vector<String>(), new Vector<String>(), new Vector<String>(), callback()));
        assertEquals(0, legacyCalls.get());
        assertEquals(1, extendedCalls.get());
    }

    @Test
    void clientRelaysLegacyAndModernHandlersWithoutLosingMetadata() {
        CefClient client = CefApp.getInstance().createClient();
        CefBrowser browser = browserProxy();
        Vector<String> filters = vector("image/*");
        Vector<String> extensions = vector(".png;.jpg");
        Vector<String> descriptions = vector("Image Files");
        CefFileDialogCallback callback = callback();
        AtomicInteger legacyCalls = new AtomicInteger();
        AtomicInteger modernCalls = new AtomicInteger();
        CefDialogHandler legacy = new CefDialogHandler() {
            @Override
            @Deprecated
            public boolean onFileDialog(CefBrowser receivedBrowser, FileDialogMode mode,
                    String title, String defaultFilePath, Vector<String> receivedFilters,
                    CefFileDialogCallback receivedCallback) {
                legacyCalls.incrementAndGet();
                assertSame(browser, receivedBrowser);
                assertSame(filters, receivedFilters);
                assertSame(callback, receivedCallback);
                return true;
            }
        };
        CefDialogHandler modern = new CefDialogHandler() {
            @Override
            public boolean onFileDialog(CefBrowser receivedBrowser, FileDialogMode mode,
                    String title, String defaultFilePath, Vector<String> receivedFilters,
                    Vector<String> receivedExtensions, Vector<String> receivedDescriptions,
                    CefFileDialogCallback receivedCallback) {
                modernCalls.incrementAndGet();
                assertSame(browser, receivedBrowser);
                assertSame(filters, receivedFilters);
                assertSame(extensions, receivedExtensions);
                assertSame(descriptions, receivedDescriptions);
                assertSame(callback, receivedCallback);
                return false;
            }
        };

        try {
            assertFalse(client.onFileDialog(browser, FileDialogMode.FILE_DIALOG_OPEN, "", "",
                    filters, extensions, descriptions, callback));
            assertSame(client, client.addDialogHandler(legacy));
            assertSame(client, client.addDialogHandler(modern));
            assertTrue(client.onFileDialog(browser, FileDialogMode.FILE_DIALOG_OPEN, "", "",
                    filters, extensions, descriptions, callback));
            assertTrue(client.onFileDialog(
                    browser, FileDialogMode.FILE_DIALOG_OPEN, "", "", filters, callback));
            assertEquals(2, legacyCalls.get());
            assertEquals(0, modernCalls.get());
            assertFalse(client.onFileDialog(null, FileDialogMode.FILE_DIALOG_OPEN, "", "", filters,
                    extensions, descriptions, callback));
            assertEquals(2, legacyCalls.get());

            client.removeDialogHandler();
            client.addDialogHandler(modern);
            assertFalse(client.onFileDialog(browser, FileDialogMode.FILE_DIALOG_SAVE, "", "",
                    filters, extensions, descriptions, callback));
            assertEquals(1, modernCalls.get());
            client.removeDialogHandler();
            assertFalse(client.onFileDialog(browser, FileDialogMode.FILE_DIALOG_OPEN, "", "",
                    filters, extensions, descriptions, callback));
        } finally {
            client.dispose();
        }
    }

    @Test
    void nativeBridgeKeepsTheExtendedDescriptorAndParallelVectorOrder() throws Exception {
        String source = readSource("native", "dialog_handler.cpp");
        String callback = sourceBetween(
                source, "bool DialogHandler::OnFileDialog(", "return (jreturn != JNI_FALSE);");

        assertTrue(callback.contains(
                "Ljava/util/Vector;Ljava/util/Vector;Ljava/util/Vector;Lorg/cef/"));
        assertTrue(callback.contains(
                "jdefaultFilePath.get(), jacceptFilters.get(), jacceptExtensions.get(),\n      jacceptDescriptions.get(), jcallback.get()"));
        assertTrue(callback.contains("if (jreturn == JNI_FALSE)"));
    }

    private static CefBrowser browserProxy() {
        return (CefBrowser) Proxy.newProxyInstance(CefBrowser.class.getClassLoader(),
                new Class<?>[] {CefBrowser.class}, (proxy, method, arguments) -> null);
    }

    private static CefFileDialogCallback callback() {
        return new CefFileDialogCallback() {
            @Override
            public void Continue(Vector<String> filePaths) {}

            @Override
            public void Cancel() {}
        };
    }

    private static Vector<String> vector(String... values) {
        Vector<String> vector = new Vector<String>();
        for (String value : values) vector.add(value);
        return vector;
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
}
