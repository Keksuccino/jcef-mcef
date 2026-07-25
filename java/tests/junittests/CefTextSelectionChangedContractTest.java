// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefPaintEvent;
import org.cef.callback.CefDragData;
import org.cef.handler.CefRenderHandler;
import org.cef.handler.CefRenderHandlerAdapter;
import org.cef.handler.CefScreenInfo;
import org.cef.misc.CefRange;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.Rectangle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

class CefTextSelectionChangedContractTest {
    private static final String CALLBACK_DESCRIPTOR =
            "(Lorg/cef/browser/CefBrowser;Ljava/lang/String;Lorg/cef/misc/CefRange;)V";

    @Test
    void callbackIsBinaryCompatibleAndUsesTheExactDescriptor() throws Exception {
        Method callback = CefRenderHandler.class.getMethod("onTextSelectionChanged", CefBrowser.class, String.class, CefRange.class);
        assertTrue(callback.isDefault());
        assertTrue(Modifier.isPublic(callback.getModifiers()));
        assertFalse(Modifier.isAbstract(callback.getModifiers()));
        assertEquals(void.class, callback.getReturnType());
        assertEquals(CALLBACK_DESCRIPTOR, MethodType.methodType(callback.getReturnType(), callback.getParameterTypes()).toMethodDescriptorString());

        Method adapter = CefRenderHandlerAdapter.class.getDeclaredMethod("onTextSelectionChanged", CefBrowser.class, String.class, CefRange.class);
        Method client = CefClient.class.getDeclaredMethod("onTextSelectionChanged", CefBrowser.class, String.class, CefRange.class);
        assertConcreteVoidMethod(adapter);
        assertConcreteVoidMethod(client);

        LegacyRenderHandler legacy = new LegacyRenderHandler();
        assertFalse(List.of(LegacyRenderHandler.class.getDeclaredMethods()).stream().anyMatch(method -> method.getName().equals("onTextSelectionChanged")));
        assertDoesNotThrow(() -> legacy.onTextSelectionChanged(null, "", new CefRange(0, 0)));
    }

    @Test
    void cefClientRoutesSynchronouslyToEachBrowserHandlerWithoutChangingIdentity() throws Exception {
        CefClient client = allocateCefClientWithoutNativeConstruction();
        RecordingRenderHandler firstHandler = new RecordingRenderHandler();
        RecordingRenderHandler secondHandler = new RecordingRenderHandler();
        AtomicInteger firstLookups = new AtomicInteger();
        AtomicInteger secondLookups = new AtomicInteger();
        CefBrowser firstBrowser = browserWithRenderHandler(firstHandler, firstLookups);
        CefBrowser secondBrowser = browserWithRenderHandler(secondHandler, secondLookups);
        String exactUtf16 = new String(new char[] {'A', '\0', '\uD83D', '\uDE00', '\uD800', 'B'});
        String logicalText = new String("logical document order");
        CefRange forward = new CefRange(1, 6);
        CefRange reversed = new CefRange(CefRange.MAX_VALUE, 0);
        CefRange collapsed = new CefRange(5, 5);
        CefRange cleared = new CefRange(0, 0);
        CefRange unavailableText = new CefRange(7, 9);
        CefRange invalid = CefRange.INVALID;

        client.onTextSelectionChanged(firstBrowser, exactUtf16, forward);
        client.onTextSelectionChanged(secondBrowser, logicalText, reversed);
        client.onTextSelectionChanged(firstBrowser, "", collapsed);
        client.onTextSelectionChanged(secondBrowser, "", unavailableText);
        client.onTextSelectionChanged(firstBrowser, "", invalid);
        client.onTextSelectionChanged(secondBrowser, "", cleared);

        assertEquals(3, firstLookups.get());
        assertEquals(3, secondLookups.get());
        assertEquals(3, firstHandler.browsers_.size());
        assertEquals(3, secondHandler.browsers_.size());
        assertSame(firstBrowser, firstHandler.browsers_.get(0));
        assertSame(firstBrowser, firstHandler.browsers_.get(1));
        assertSame(firstBrowser, firstHandler.browsers_.get(2));
        assertSame(secondBrowser, secondHandler.browsers_.get(0));
        assertSame(secondBrowser, secondHandler.browsers_.get(1));
        assertSame(secondBrowser, secondHandler.browsers_.get(2));
        assertSame(exactUtf16, firstHandler.texts_.get(0));
        assertSame(forward, firstHandler.ranges_.get(0));
        assertEquals("", firstHandler.texts_.get(1));
        assertSame(collapsed, firstHandler.ranges_.get(1));
        assertEquals("", firstHandler.texts_.get(2));
        assertSame(invalid, firstHandler.ranges_.get(2));
        assertSame(logicalText, secondHandler.texts_.get(0));
        assertSame(reversed, secondHandler.ranges_.get(0));
        assertEquals("", secondHandler.texts_.get(1));
        assertSame(unavailableText, secondHandler.ranges_.get(1));
        assertEquals("", secondHandler.texts_.get(2));
        assertSame(cleared, secondHandler.ranges_.get(2));

        assertDoesNotThrow(() -> client.onTextSelectionChanged(null, exactUtf16, forward));
        assertDoesNotThrow(() -> client.onTextSelectionChanged(browserWithRenderHandler(null, new AtomicInteger()), exactUtf16, forward));
        assertEquals(3, firstHandler.ranges_.size());
        assertEquals(3, secondHandler.ranges_.size());
    }

    @Test
    void nativeBridgeCreatesAtomicUtf16AndUnsignedRangeSnapshotsOnTheCefUiThread() throws Exception {
        String implementation = source("native", "render_handler.cpp");
        String callback = section(implementation, "void RenderHandler::OnTextSelectionChanged(", "bool RenderHandler::StartDragging(");
        // Native popups and browsers already leaving the Java lifecycle can have no Java wrapper,
        // so the bridge must reject that state before allocating either callback payload.
        assertOrdered(callback, "REQUIRE_UI_THREAD();", "ScopedJNIBrowser jbrowser(env, browser);", "DescribeAndClearJNIException(env) || !jbrowser", "NewJNIString(env, selected_text)", "DescribeAndClearJNIException(env)", "NewJNICefRange(env, selected_range)", "DescribeAndClearJNIException(env)", "JNI_CALL_VOID_METHOD(env, handle_, \"onTextSelectionChanged\"");
        assertTrue(callback.contains(CALLBACK_DESCRIPTOR));

        String header = normalizeWhitespace(source("native", "render_handler.h"));
        assertTrue(header.contains("virtual void OnTextSelectionChanged(CefRefPtr<CefBrowser> browser, const CefString& selected_text, const CefRange& selected_range) override;"));
        assertTrue(implementation.contains("static_assert(CEF_API_VERSION == 15100, \"CEF API changed: re-audit the text-selection callback and Java bridge contract\");"));

        String utilityImplementation = source("native", "jni_util.cpp");
        String stringConverter = section(utilityImplementation, "jstring NewJNIString(JNIEnv* env, const CefString& str)", "jstring NewJNIString(JNIEnv* env, const char* str)");
        assertTrue(stringConverter.contains("str.empty() ? u\"\" : str.c_str()"));
        assertTrue(stringConverter.contains("std::u16string_view(chars, str.length())"));
        assertFalse(stringConverter.contains("ToString()"));

        String rangeConverter = section(utilityImplementation, "jobject NewJNICefRange(", "CefRect GetJNIRect(");
        assertTrue(rangeConverter.contains("\"(JJ)V\""));
        assertTrue(rangeConverter.contains("static_cast<jlong>(range.from)"));
        assertTrue(rangeConverter.contains("static_cast<jlong>(range.to)"));
    }

    private static void assertConcreteVoidMethod(Method method) {
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertFalse(Modifier.isAbstract(method.getModifiers()));
        assertEquals(void.class, method.getReturnType());
        assertEquals(CALLBACK_DESCRIPTOR, MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString());
    }

    private static CefClient allocateCefClientWithoutNativeConstruction() throws Exception {
        // This callback relay reads no CefClient state. Allocating without its package-private
        // constructor keeps this contract test native-independent; that constructor creates the
        // JNI client handler and would make browser-specific routing impossible to isolate.
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeClass.getDeclaredField("theUnsafe");
        assertTrue(singleton.trySetAccessible(), "Java 17 must expose sun.misc.Unsafe through jdk.unsupported");
        Object unsafe = singleton.get(null);
        Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
        return (CefClient) allocateInstance.invoke(unsafe, CefClient.class);
    }

    private static CefBrowser browserWithRenderHandler(CefRenderHandler renderHandler, AtomicInteger lookups) {
        InvocationHandler invocationHandler = (proxy, method, arguments) -> {
            if (method.getName().equals("getRenderHandler")) {
                lookups.incrementAndGet();
                return renderHandler;
            }
            if (method.getDeclaringClass() == Object.class) {
                if (method.getName().equals("equals")) return proxy == arguments[0];
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("toString"))
                    return "CefBrowser text-selection routing proxy";
            }
            throw new UnsupportedOperationException(method.getName());
        };
        return (CefBrowser) Proxy.newProxyInstance(CefBrowser.class.getClassLoader(), new Class<?>[] {CefBrowser.class}, invocationHandler);
    }

    private static String source(String first, String second) throws Exception {
        Path path = Path.of(System.getProperty("user.dir"), first, second);
        assertTrue(Files.isRegularFile(path), "Run source contract tests from the repository root: " + path);
        return Files.readString(path).replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String section(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0, () -> "Missing source marker: " + startMarker);
        assertTrue(end > start, () -> "Missing source boundary: " + endMarker);
        return source.substring(start, end);
    }

    private static String normalizeWhitespace(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private static void assertOrdered(String source, String... markers) {
        int previous = -1;
        for (String marker : markers) {
            int current = source.indexOf(marker, previous + 1);
            assertTrue(current > previous, () -> "Missing or out-of-order source marker: " + marker);
            previous = current;
        }
    }

    private static final class RecordingRenderHandler extends CefRenderHandlerAdapter {
        private final List<CefBrowser> browsers_ = new ArrayList<CefBrowser>();
        private final List<String> texts_ = new ArrayList<String>();
        private final List<CefRange> ranges_ = new ArrayList<CefRange>();

        @Override
        public void onTextSelectionChanged(CefBrowser browser, String selectedText, CefRange selectedRange) {
            browsers_.add(browser);
            texts_.add(selectedText);
            ranges_.add(selectedRange);
        }
    }

    private static final class LegacyRenderHandler implements CefRenderHandler {
        @Override
        public Rectangle getViewRect(CefBrowser browser) {
            return new Rectangle();
        }

        @Override
        public boolean getScreenInfo(CefBrowser browser, CefScreenInfo screenInfo) {
            return false;
        }

        @Override
        public Point getScreenPoint(CefBrowser browser, Point viewPoint) {
            return new Point();
        }

        @Override
        public void onPopupShow(CefBrowser browser, boolean show) {}

        @Override
        public void onPopupSize(CefBrowser browser, Rectangle size) {}

        @Override
        public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {}

        @Override
        public void addOnPaintListener(Consumer<CefPaintEvent> listener) {}

        @Override
        public void setOnPaintListener(Consumer<CefPaintEvent> listener) {}

        @Override
        public void removeOnPaintListener(Consumer<CefPaintEvent> listener) {}

        @Override
        public boolean onCursorChange(CefBrowser browser, int cursorType) {
            return false;
        }

        @Override
        public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y) {
            return false;
        }

        @Override
        public void updateDragCursor(CefBrowser browser, int operation) {}
    }
}
