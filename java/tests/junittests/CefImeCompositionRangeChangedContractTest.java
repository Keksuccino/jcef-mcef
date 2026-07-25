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

class CefImeCompositionRangeChangedContractTest {
    private static final String CALLBACK_DESCRIPTOR = "(Lorg/cef/browser/CefBrowser;Lorg/cef/misc/CefRange;[Ljava/awt/Rectangle;)V";

    @Test
    void callbackIsAnExactBinaryCompatibleDefaultAndConcreteOnAdapterAndClient() throws Exception {
        Method callback = CefRenderHandler.class.getMethod("onImeCompositionRangeChanged", CefBrowser.class, CefRange.class, Rectangle[].class);
        assertTrue(callback.isDefault());
        assertTrue(Modifier.isPublic(callback.getModifiers()));
        assertFalse(Modifier.isAbstract(callback.getModifiers()));
        assertEquals(void.class, callback.getReturnType());
        assertEquals(CALLBACK_DESCRIPTOR, MethodType.methodType(callback.getReturnType(), callback.getParameterTypes()).toMethodDescriptorString());

        Method adapter = CefRenderHandlerAdapter.class.getDeclaredMethod("onImeCompositionRangeChanged", CefBrowser.class, CefRange.class, Rectangle[].class);
        Method client = CefClient.class.getDeclaredMethod("onImeCompositionRangeChanged", CefBrowser.class, CefRange.class, Rectangle[].class);
        assertConcreteVoidMethod(adapter);
        assertConcreteVoidMethod(client);

        LegacyRenderHandler legacy = new LegacyRenderHandler();
        assertFalse(List.of(LegacyRenderHandler.class.getDeclaredMethods()).stream().anyMatch(method -> method.getName().equals("onImeCompositionRangeChanged")));
        assertDoesNotThrow(() -> legacy.onImeCompositionRangeChanged(null, CefRange.INVALID, new Rectangle[0]));
    }

    @Test
    void cefClientRoutesSynchronouslyWithoutChangingObjectIdentityAndNoOpsWhenUnavailable() throws Exception {
        CefClient client = allocateCefClientWithoutNativeConstruction();
        RecordingRenderHandler handler = new RecordingRenderHandler();
        AtomicInteger handlerLookups = new AtomicInteger();
        CefBrowser browser = browserWithRenderHandler(handler, handlerLookups);
        Rectangle[] bounds = {new Rectangle(7, 11, 13, 17)};
        CefRange full = new CefRange(0, CefRange.MAX_VALUE);
        CefRange reversed = new CefRange(CefRange.MAX_VALUE, 0);

        client.onImeCompositionRangeChanged(browser, full, bounds);
        client.onImeCompositionRangeChanged(browser, reversed, bounds);
        client.onImeCompositionRangeChanged(browser, CefRange.INVALID, bounds);

        assertEquals(3, handlerLookups.get());
        assertEquals(3, handler.browsers_.size());
        for (CefBrowser deliveredBrowser : handler.browsers_) assertSame(browser, deliveredBrowser);
        assertSame(full, handler.ranges_.get(0));
        assertSame(reversed, handler.ranges_.get(1));
        assertSame(CefRange.INVALID, handler.ranges_.get(2));
        for (Rectangle[] deliveredBounds : handler.bounds_) assertSame(bounds, deliveredBounds);
        assertEquals(0, handler.ranges_.get(0).getFrom());
        assertEquals(CefRange.MAX_VALUE, handler.ranges_.get(0).getTo());
        assertEquals(CefRange.MAX_VALUE, handler.ranges_.get(1).getFrom());
        assertEquals(0, handler.ranges_.get(1).getTo());
        assertFalse(handler.ranges_.get(2).isValid());

        assertDoesNotThrow(() -> client.onImeCompositionRangeChanged(null, full, bounds));
        assertDoesNotThrow(() -> client.onImeCompositionRangeChanged(browserWithRenderHandler(null, new AtomicInteger()), full, bounds));
        assertEquals(3, handler.ranges_.size());
    }

    @Test
    void nativeBridgeHasTheExactSignatureDescriptorAndAtomicSnapshotContracts() throws Exception {
        String implementation = source("native", "render_handler.cpp");
        String callback = section(implementation, "void RenderHandler::OnImeCompositionRangeChanged(", "bool RenderHandler::StartDragging(");
        assertOrdered(callback, "REQUIRE_UI_THREAD();", "ScopedJNIBrowser jbrowser(env, browser);", "if (!jbrowser)", "NewJNICefRange(env, selected_range)", "if (!jselected_range)", "NewJNIRectArray(env, character_bounds)", "if (!jcharacter_bounds)", "JNI_CALL_VOID_METHOD(env, handle_, \"onImeCompositionRangeChanged\"");
        assertTrue(callback.contains(CALLBACK_DESCRIPTOR));

        String header = normalizeWhitespace(source("native", "render_handler.h"));
        assertTrue(header.contains("virtual void OnImeCompositionRangeChanged(CefRefPtr<CefBrowser> browser, const CefRange& selected_range, const RectList& character_bounds) override;"));

        String utilityHeader = normalizeWhitespace(source("native", "jni_util.h"));
        assertTrue(utilityHeader.contains("jobject NewJNICefRange(JNIEnv* env, const CefRange& range);"));
        String utilityImplementation = source("native", "jni_util.cpp");
        String rangeConverter = section(utilityImplementation, "jobject NewJNICefRange(", "CefRect GetJNIRect(");
        assertTrue(rangeConverter.contains("\"org/cef/misc/CefRange\""));
        assertTrue(rangeConverter.contains("\"(JJ)V\""));
        assertTrue(rangeConverter.contains("static_cast<jlong>(range.from)"));
        assertTrue(rangeConverter.contains("static_cast<jlong>(range.to)"));
        assertOrdered(rangeConverter, "DescribeAndClearJNIException(env)", "ScopedJNIClass cls", "GetMethodID", "NewObject", "result.Release()");

        String rectConverter = section(implementation, "jobjectArray NewJNIRectArray(", "jobject NewJNIPoint(");
        assertFalse(rectConverter.contains("vals.empty()"), "Empty CEF lists must not become null Java arrays");
        assertTrue(rectConverter.contains("std::numeric_limits<jsize>::max()"));
        assertTrue(rectConverter.contains("env->NewObjectArray(size, cls, nullptr)"));
        assertTrue(rectConverter.contains("NewJNIRect(env, vals[i])"));
        assertTrue(rectConverter.contains("SetObjectArrayElement"));
        assertOrdered(rectConverter, "NewObjectArray", "if (DescribeAndClearJNIException(env) || !arr)", "NewJNIRect", "if (DescribeAndClearJNIException(env) || !rect_obj)", "SetObjectArrayElement", "if (DescribeAndClearJNIException(env))", "arr.Release()");
    }

    private static void assertConcreteVoidMethod(Method method) {
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertFalse(Modifier.isAbstract(method.getModifiers()));
        assertEquals(void.class, method.getReturnType());
        assertEquals(CALLBACK_DESCRIPTOR, MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString());
    }

    private static CefClient allocateCefClientWithoutNativeConstruction() throws Exception {
        // This callback relay reads no CefClient state. Allocating without its package-private
        // constructor keeps this contract test native-independent; the constructor deliberately
        // creates the JNI client handler and would make routing semantics impossible to isolate.
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
                if (method.getName().equals("toString")) return "CefBrowser render-handler routing proxy";
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
        private final List<CefRange> ranges_ = new ArrayList<CefRange>();
        private final List<Rectangle[]> bounds_ = new ArrayList<Rectangle[]>();

        @Override
        public void onImeCompositionRangeChanged(CefBrowser browser, CefRange selectedRange, Rectangle[] characterBounds) {
            browsers_.add(browser);
            ranges_.add(selectedRange);
            bounds_.add(characterBounds);
        }
    }

    private static final class LegacyRenderHandler implements CefRenderHandler {
        @Override
        public Rectangle getViewRect(CefBrowser browser) { return new Rectangle(); }

        @Override
        public boolean getScreenInfo(CefBrowser browser, CefScreenInfo screenInfo) { return false; }

        @Override
        public Point getScreenPoint(CefBrowser browser, Point viewPoint) { return new Point(); }

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
        public boolean onCursorChange(CefBrowser browser, int cursorType) { return false; }

        @Override
        public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y) { return false; }

        @Override
        public void updateDragCursor(CefBrowser browser, int operation) {}
    }
}
