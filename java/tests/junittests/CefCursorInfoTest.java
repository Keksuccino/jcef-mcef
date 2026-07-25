// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefDisplayHandler;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefRenderHandler;
import org.cef.handler.CefRenderHandlerAdapter;
import org.cef.misc.CefCursorInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.file.Files;
import java.nio.file.Path;

class CefCursorInfoTest {
    @Test
    void snapshotsMetadataAndDefensivelyOwnsPixels() {
        byte[] source = {10, 20, 30, 40, 50, 60, 70, 80};
        byte[] expected = source.clone();
        CefCursorInfo cursorInfo = new CefCursorInfo(1, 0, 2.0f, 2, 1, source);

        source[0] = 99;
        assertEquals(1, cursorInfo.getHotspotX());
        assertEquals(0, cursorInfo.getHotspotY());
        assertEquals(2.0f, cursorInfo.getImageScaleFactor());
        assertEquals(2, cursorInfo.getWidth());
        assertEquals(1, cursorInfo.getHeight());

        ByteBuffer first = cursorInfo.getBuffer();
        assertTrue(first.isReadOnly());
        assertFalse(first.hasArray());
        assertEquals(0, first.position());
        assertEquals(expected.length, first.remaining());
        assertArrayEquals(expected, readRemaining(first));
        assertThrows(ReadOnlyBufferException.class, () -> first.put(0, (byte) 1));

        ByteBuffer second = cursorInfo.getBuffer();
        assertNotSame(first, second);
        assertEquals(0, second.position());
        assertArrayEquals(expected, readRemaining(second));
    }

    @Test
    void supportsTheEmptyCustomCursorRepresentedByChromium() {
        CefCursorInfo fromEmptyArray = new CefCursorInfo(0, 0, 1.0f, 0, 0, new byte[0]);
        CefCursorInfo fromNullBuffer = new CefCursorInfo(0, 0, 1.0f, 0, 0, null);

        for (CefCursorInfo cursorInfo : new CefCursorInfo[] {fromEmptyArray, fromNullBuffer}) {
            assertEquals(0, cursorInfo.getWidth());
            assertEquals(0, cursorInfo.getHeight());
            assertEquals(0, cursorInfo.getBuffer().remaining());
            assertTrue(cursorInfo.getBuffer().isReadOnly());
        }
    }

    @Test
    void rejectsInvalidDimensionsHotspotsScalesAndBufferLengths() {
        byte[] pixel = {1, 2, 3, 4};
        assertThrows(IllegalArgumentException.class, () -> new CefCursorInfo(0, 0, 1.0f, -1, 1, pixel));
        assertThrows(IllegalArgumentException.class, () -> new CefCursorInfo(0, 0, 1.0f, 1, -1, pixel));
        assertThrows(IllegalArgumentException.class, () -> new CefCursorInfo(0, 0, 1.0f, 0, 1, pixel));
        assertThrows(IllegalArgumentException.class, () -> new CefCursorInfo(0, 0, 1.0f, 1, 0, pixel));
        assertThrows(IllegalArgumentException.class, () -> new CefCursorInfo(1, 0, 1.0f, 0, 0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new CefCursorInfo(0, 1, 1.0f, 0, 0, new byte[0]));

        assertThrows(IllegalArgumentException.class, () -> new CefCursorInfo(-1, 0, 1.0f, 1, 1, pixel));
        assertThrows(IllegalArgumentException.class, () -> new CefCursorInfo(0, -1, 1.0f, 1, 1, pixel));
        assertThrows(IllegalArgumentException.class, () -> new CefCursorInfo(1, 0, 1.0f, 1, 1, pixel));
        assertThrows(IllegalArgumentException.class, () -> new CefCursorInfo(0, 1, 1.0f, 1, 1, pixel));

        for (float scale : new float[] {
                     0.0f, -1.0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> new CefCursorInfo(0, 0, scale, 1, 1, pixel));
        new CefCursorInfo(0, 0, Float.MIN_VALUE, 1, 1, pixel);
        new CefCursorInfo(0, 0, Float.MAX_VALUE, 1, 1, pixel);

        assertThrows(IllegalArgumentException.class, () -> new CefCursorInfo(0, 0, 1.0f, 1, 1, null));
        assertThrows(IllegalArgumentException.class, () -> new CefCursorInfo(0, 0, 1.0f, 1, 1, new byte[3]));
        assertThrows(IllegalArgumentException.class, () -> new CefCursorInfo(0, 0, 1.0f, 1, 1, new byte[5]));
    }

    @Test
    void rejectsOverflowBeforeCopyingTheSuppliedBuffer() {
        byte[] tinyBuffer = new byte[0];
        assertThrows(IllegalArgumentException.class, () -> new CefCursorInfo(0, 0, 1.0f, 536_870_912, 1, tinyBuffer));
        assertThrows(IllegalArgumentException.class, () -> new CefCursorInfo(0, 0, 1.0f, Integer.MAX_VALUE, Integer.MAX_VALUE, tinyBuffer));
    }

    @Test
    void exposesOnlyFinalStateAndReadOnlyPixelViews() throws Exception {
        assertTrue(Modifier.isFinal(CefCursorInfo.class.getModifiers()));
        for (Field field : CefCursorInfo.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            assertTrue(Modifier.isPrivate(field.getModifiers()), field.getName());
            assertTrue(Modifier.isFinal(field.getModifiers()), field.getName());
            assertFalse(field.getType() == long.class, "A platform cursor handle must not be retained");
        }

        Method getBuffer = CefCursorInfo.class.getMethod("getBuffer");
        assertEquals(ByteBuffer.class, getBuffer.getReturnType());
        Method nativeFactory = CefCursorInfo.class.getDeclaredMethod("createNative", int.class, int.class, float.class, int.class, int.class, ByteBuffer.class);
        assertTrue(Modifier.isPrivate(nativeFactory.getModifiers()));
        assertTrue(Modifier.isStatic(nativeFactory.getModifiers()));
    }

    @Test
    void handlerOverloadsAreDefaultAndRelaysDeclareTheOwnedSnapshotApi() throws Exception {
        Method displayMethod = CefDisplayHandler.class.getMethod("onCursorChange", CefBrowser.class, int.class, CefCursorInfo.class);
        Method renderMethod = CefRenderHandler.class.getMethod("onCursorChange", CefBrowser.class, int.class, CefCursorInfo.class);
        assertTrue(displayMethod.isDefault());
        assertTrue(renderMethod.isDefault());

        Method displayAdapterMethod = CefDisplayHandlerAdapter.class.getDeclaredMethod("onCursorChange", CefBrowser.class, int.class, CefCursorInfo.class);
        Method renderAdapterMethod = CefRenderHandlerAdapter.class.getDeclaredMethod("onCursorChange", CefBrowser.class, int.class, CefCursorInfo.class);
        Method clientMethod = CefClient.class.getDeclaredMethod("onCursorChange", CefBrowser.class, int.class, CefCursorInfo.class);
        assertFalse(Modifier.isAbstract(displayAdapterMethod.getModifiers()));
        assertFalse(Modifier.isAbstract(renderAdapterMethod.getModifiers()));
        assertFalse(Modifier.isAbstract(clientMethod.getModifiers()));
    }

    @Test
    void nativeBridgeCopiesOnlyCustomPixelsAndInvokesTheExactOwnedSnapshotDescriptor() throws Exception {
        String source = readSource("native", "display_handler.cpp");
        String callback = sourceBetween(source, "bool DisplayHandler::OnCursorChange(", "return (jreturn != JNI_FALSE);\n}");
        assertTrue(source.contains("NewJNIByteBuffer(env, cursor_info.buffer, buffer_size)"));
        assertTrue(source.contains("(IIFIILjava/nio/ByteBuffer;)Lorg/cef/misc/CefCursorInfo;"));
        assertTrue(source.contains("std::numeric_limits<jsize>::max()"));
        assertTrue(callback.contains("type == CT_CUSTOM ? NewJNICursorInfo(env, custom_cursor_info)"));
        assertTrue(callback.contains("(Lorg/cef/browser/CefBrowser;ILorg/cef/misc/CefCursorInfo;)Z"));
        assertTrue(callback.contains("static_cast<void>(cursor);"));
    }

    @Test
    void clientFanoutKeepsDisplayFirstShortCircuitAndSharesOneSnapshot() throws Exception {
        String source = readSource("java", "org", "cef", "CefClient.java");
        String dispatch = sourceBetween(source, "private boolean dispatchCursorChange(", "// CefDownloadHandler");
        String displayCall =
                "displayHandler_.onCursorChange(browser, cursorType, customCursorInfo)";
        String renderCall = "realHandler.onCursorChange(browser, cursorType, customCursorInfo)";
        int displayIndex = dispatch.indexOf(displayCall);
        int earlyReturnIndex = dispatch.indexOf("return true;", displayIndex);
        int renderIndex = dispatch.indexOf(renderCall);
        assertTrue(displayIndex >= 0);
        assertTrue(earlyReturnIndex > displayIndex);
        assertTrue(renderIndex > earlyReturnIndex);
    }

    private static byte[] readRemaining(ByteBuffer buffer) {
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    private static String readSource(String first, String... more) throws Exception {
        Path sourcePath = Path.of(System.getProperty("user.dir"), first);
        for (String pathElement : more) sourcePath = sourcePath.resolve(pathElement);
        assertTrue(Files.isRegularFile(sourcePath), "Run source contract tests from the repository root");
        return Files.readString(sourcePath).replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String sourceBetween(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue(start >= 0, "Missing source marker: " + startMarker);
        assertTrue(end > start, "Missing source marker after " + startMarker + ": " + endMarker);
        return source.substring(start, end);
    }
}
