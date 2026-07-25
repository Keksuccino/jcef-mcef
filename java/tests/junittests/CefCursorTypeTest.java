// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.misc.CefCursorType;
import org.junit.jupiter.api.Test;

import java.awt.Cursor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

class CefCursorTypeTest {
    private static final CefCursorType[] CEF_TYPES_BY_ID = {CefCursorType.POINTER,
            CefCursorType.CROSS, CefCursorType.HAND, CefCursorType.IBEAM, CefCursorType.WAIT,
            CefCursorType.HELP, CefCursorType.EAST_RESIZE, CefCursorType.NORTH_RESIZE,
            CefCursorType.NORTH_EAST_RESIZE, CefCursorType.NORTH_WEST_RESIZE,
            CefCursorType.SOUTH_RESIZE, CefCursorType.SOUTH_EAST_RESIZE,
            CefCursorType.SOUTH_WEST_RESIZE, CefCursorType.WEST_RESIZE,
            CefCursorType.NORTH_SOUTH_RESIZE, CefCursorType.EAST_WEST_RESIZE,
            CefCursorType.NORTH_EAST_SOUTH_WEST_RESIZE, CefCursorType.NORTH_WEST_SOUTH_EAST_RESIZE,
            CefCursorType.COLUMN_RESIZE, CefCursorType.ROW_RESIZE, CefCursorType.MIDDLE_PANNING,
            CefCursorType.EAST_PANNING, CefCursorType.NORTH_PANNING,
            CefCursorType.NORTH_EAST_PANNING, CefCursorType.NORTH_WEST_PANNING,
            CefCursorType.SOUTH_PANNING, CefCursorType.SOUTH_EAST_PANNING,
            CefCursorType.SOUTH_WEST_PANNING, CefCursorType.WEST_PANNING, CefCursorType.MOVE,
            CefCursorType.VERTICAL_IBEAM, CefCursorType.CELL, CefCursorType.CONTEXT_MENU,
            CefCursorType.ALIAS, CefCursorType.PROGRESS, CefCursorType.NO_DROP, CefCursorType.COPY,
            CefCursorType.NONE, CefCursorType.NOT_ALLOWED, CefCursorType.ZOOM_IN,
            CefCursorType.ZOOM_OUT, CefCursorType.GRAB, CefCursorType.GRABBING,
            CefCursorType.MIDDLE_PANNING_VERTICAL, CefCursorType.MIDDLE_PANNING_HORIZONTAL,
            CefCursorType.CUSTOM, CefCursorType.DND_NONE, CefCursorType.DND_MOVE,
            CefCursorType.DND_COPY, CefCursorType.DND_LINK};

    private static final CefCursorType[] LEGACY_DECLARATION_ORDER = {CefCursorType.POINTER,
            CefCursorType.CROSS, CefCursorType.HAND, CefCursorType.IBEAM, CefCursorType.WAIT,
            CefCursorType.HELP, CefCursorType.EAST_RESIZE, CefCursorType.NORTH_RESIZE,
            CefCursorType.NORTH_EAST_RESIZE, CefCursorType.NORTH_WEST_RESIZE,
            CefCursorType.SOUTH_RESIZE, CefCursorType.SOUTH_EAST_RESIZE,
            CefCursorType.SOUTH_WEST_RESIZE, CefCursorType.WEST_RESIZE,
            CefCursorType.NORTH_SOUTH_RESIZE, CefCursorType.EAST_WEST_RESIZE,
            CefCursorType.NORTH_EAST_SOUTH_WEST_RESIZE, CefCursorType.NORTH_WEST_SOUTH_EAST_RESIZE,
            CefCursorType.COLUMN_RESIZE, CefCursorType.ROW_RESIZE, CefCursorType.MIDDLE_PANNING,
            CefCursorType.EAST_PANNING, CefCursorType.NORTH_PANNING,
            CefCursorType.NORTH_EAST_PANNING, CefCursorType.NORTH_WEST_PANNING,
            CefCursorType.SOUTH_PANNING, CefCursorType.SOUTH_EAST_PANNING,
            CefCursorType.SOUTH_WEST_PANNING, CefCursorType.WEST_PANNING, CefCursorType.MOVE,
            CefCursorType.VERTICAL_IBEAM, CefCursorType.CELL, CefCursorType.CONTEXT_MENU,
            CefCursorType.ALIAS, CefCursorType.PROGRESS, CefCursorType.NO_DROP, CefCursorType.COPY,
            CefCursorType.NONE, CefCursorType.NOT_ALLOWED, CefCursorType.ZOOM_IN,
            CefCursorType.ZOOM_OUT, CefCursorType.GRAB, CefCursorType.GRABBING,
            CefCursorType.CUSTOM};

    private static final int[] LEGACY_GLFW_IDS = {0, 0x36003, 0x36004, 0x36002, 0, 0, 0x36005,
            0x36006, 0x36008, 0x36007, 0x36006, 0x36007, 0x36008, 0x36005, 0x36006, 0x36005,
            0x36008, 0x36007, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x36009, 0, 0, 0, 0, 0, 0x3600A, 0,
            0, 0x3600A, 0, 0, 0, 0, 0};

    private static final String[] CEF_NATIVE_CURSOR_NAMES = {"CT_POINTER", "CT_CROSS", "CT_HAND",
            "CT_IBEAM", "CT_WAIT", "CT_HELP", "CT_EASTRESIZE", "CT_NORTHRESIZE",
            "CT_NORTHEASTRESIZE", "CT_NORTHWESTRESIZE", "CT_SOUTHRESIZE", "CT_SOUTHEASTRESIZE",
            "CT_SOUTHWESTRESIZE", "CT_WESTRESIZE", "CT_NORTHSOUTHRESIZE", "CT_EASTWESTRESIZE",
            "CT_NORTHEASTSOUTHWESTRESIZE", "CT_NORTHWESTSOUTHEASTRESIZE", "CT_COLUMNRESIZE",
            "CT_ROWRESIZE", "CT_MIDDLEPANNING", "CT_EASTPANNING", "CT_NORTHPANNING",
            "CT_NORTHEASTPANNING", "CT_NORTHWESTPANNING", "CT_SOUTHPANNING", "CT_SOUTHEASTPANNING",
            "CT_SOUTHWESTPANNING", "CT_WESTPANNING", "CT_MOVE", "CT_VERTICALTEXT", "CT_CELL",
            "CT_CONTEXTMENU", "CT_ALIAS", "CT_PROGRESS", "CT_NODROP", "CT_COPY", "CT_NONE",
            "CT_NOTALLOWED", "CT_ZOOMIN", "CT_ZOOMOUT", "CT_GRAB", "CT_GRABBING",
            "CT_MIDDLE_PANNING_VERTICAL", "CT_MIDDLE_PANNING_HORIZONTAL", "CT_CUSTOM",
            "CT_DND_NONE", "CT_DND_MOVE", "CT_DND_COPY", "CT_DND_LINK"};

    @Test
    void decodesAndRoundTripsEveryRawCefCursorId() {
        assertEquals(50, CEF_TYPES_BY_ID.length);
        for (int id = 0; id < CEF_TYPES_BY_ID.length; id++) {
            CefCursorType expected = CEF_TYPES_BY_ID[id];
            assertSame(expected, CefCursorType.fromId(id), "raw CEF cursor ID " + id);
            assertEquals(id, expected.getId(), expected.name());
            assertSame(expected, CefCursorType.fromId(expected.getId()), expected.name());
        }
    }

    @Test
    void preservesLegacyDeclarationOrderAndGlfwCompatibility() throws Exception {
        CefCursorType[] values = CefCursorType.values();
        assertArrayEquals(LEGACY_DECLARATION_ORDER, Arrays.copyOf(values, LEGACY_DECLARATION_ORDER.length));
        assertEquals(43, CefCursorType.CUSTOM.ordinal());
        assertEquals(45, CefCursorType.CUSTOM.getId());

        Field glfwId = CefCursorType.class.getField("glfwId");
        assertTrue(Modifier.isPublic(glfwId.getModifiers()));
        assertTrue(Modifier.isFinal(glfwId.getModifiers()));
        assertEquals(int.class, glfwId.getType());
        assertEquals(LEGACY_DECLARATION_ORDER.length, LEGACY_GLFW_IDS.length);
        for (int ordinal = 0; ordinal < LEGACY_DECLARATION_ORDER.length; ordinal++)
            assertEquals(LEGACY_GLFW_IDS[ordinal], LEGACY_DECLARATION_ORDER[ordinal].glfwId, LEGACY_DECLARATION_ORDER[ordinal].name());
    }

    @Test
    void mapsInvalidAndFutureIdsToUnknown() {
        assertEquals(-1, CefCursorType.UNKNOWN.getId());
        for (int id : new int[] {Integer.MIN_VALUE, -1, 50, 51, Integer.MAX_VALUE})
            assertSame(CefCursorType.UNKNOWN, CefCursorType.fromId(id), "cursor ID " + id);
    }

    @Test
    void mapsEveryRawIdToTheEstablishedConstructibleAwtCursor() throws Exception {
        Class<?> mapperClass = Class.forName("org.cef.browser.CefCursorAwt");
        assertTrue(Modifier.isFinal(mapperClass.getModifiers()));
        assertFalse(Modifier.isPublic(mapperClass.getModifiers()));
        Method fromCefId = mapperClass.getDeclaredMethod("fromCefId", int.class);
        fromCefId.setAccessible(true);

        for (int id = 0; id < CEF_TYPES_BY_ID.length; id++) {
            Cursor cursor = (Cursor) fromCefId.invoke(null, id);
            assertNotNull(cursor, "raw CEF cursor ID " + id);
            assertEquals(expectedAwtCursorType(CEF_TYPES_BY_ID[id]), cursor.getType(), CEF_TYPES_BY_ID[id].name());
            assertDoesNotThrow(() -> Cursor.getPredefinedCursor(cursor.getType()));
        }
        for (int id : new int[] {-1, 50, Integer.MAX_VALUE}) {
            Cursor cursor = (Cursor) fromCefId.invoke(null, id);
            assertEquals(Cursor.DEFAULT_CURSOR, cursor.getType(), "cursor ID " + id);
        }
    }

    @Test
    void swingSurfaceUsesTheRawCefToAwtMapper() throws Exception {
        String source = readSource("java", "org", "cef", "browser", "CefBrowserOsrSwing.java");
        String callback = sourceBetween(source, "public boolean onCursorChange(CefBrowser browser, final int cursorType) {", "private static final class SyntheticDragGestureRecognizer");
        int invokeLater = callback.indexOf("SwingUtilities.invokeLater(");
        int runnableBody = callback.indexOf("public void run() {", invokeLater);
        int mappedApplication = callback.indexOf("canvas_.setCursor(CefCursorAwt.fromCefId(cursorType));", runnableBody);
        int runnableEnd = callback.indexOf("\n            }\n        });", runnableBody);
        assertTrue(invokeLater >= 0);
        assertTrue(runnableBody > invokeLater);
        assertTrue(mappedApplication > runnableBody);
        assertTrue(runnableEnd > mappedApplication);
        assertFalse(callback.contains("new Cursor(cursorType)"));
    }

    @Test
    void nativeBridgePinsTheAuditedCefIdsAndStillForwardsRawValues() throws Exception {
        String source = readSource("native", "display_handler.cpp");
        assertEquals(50, CEF_NATIVE_CURSOR_NAMES.length);
        for (int id = 0; id < CEF_NATIVE_CURSOR_NAMES.length; id++)
            assertTrue(source.contains("static_assert(" + CEF_NATIVE_CURSOR_NAMES[id] + " == " + id + ");"), "native cursor ID " + id);
        assertTrue(source.contains("static_assert(CT_NUM_VALUES == 50);"));

        String callback = sourceBetween(source, "bool DisplayHandler::OnCursorChange(", "return (jreturn != JNI_FALSE);\n}");
        assertTrue(callback.contains("const int cursorId = (int) type;"));
        assertTrue(callback.contains("type == CT_CUSTOM ? NewJNICursorInfo(env, custom_cursor_info)"));
        assertTrue(callback.contains("(Lorg/cef/browser/CefBrowser;ILorg/cef/misc/CefCursorInfo;)Z"));
        assertTrue(callback.contains("jbrowser.get(), cursorId, jcustom_cursor_info.get()"));
        assertFalse(callback.contains("(Lorg/cef/browser/CefBrowser;I)Z"));
    }

    @Test
    void handlerContractsDocumentRawCefCursorSemantics() throws Exception {
        String displayHandler = readSource("java", "org", "cef", "handler", "CefDisplayHandler.java");
        String renderHandler = readSource("java", "org", "cef", "handler", "CefRenderHandler.java");
        for (String source : new String[] {displayHandler, renderHandler}) {
            assertTrue(source.contains("The raw numeric {@code cef_cursor_type_t} value from CEF."));
            assertTrue(source.contains("{@link org.cef.misc.CefCursorType#fromId(int)}"));
            assertTrue(source.contains("it is not an AWT predefined-cursor ID."));
        }
    }

    private static int expectedAwtCursorType(CefCursorType cursorType) {
        switch (cursorType) {
            case CROSS:
                return Cursor.CROSSHAIR_CURSOR;
            case HAND:
                return Cursor.HAND_CURSOR;
            case IBEAM:
                return Cursor.TEXT_CURSOR;
            case WAIT:
                return Cursor.WAIT_CURSOR;
            case EAST_RESIZE:
                return Cursor.E_RESIZE_CURSOR;
            case NORTH_RESIZE:
                return Cursor.N_RESIZE_CURSOR;
            case NORTH_EAST_RESIZE:
                return Cursor.NE_RESIZE_CURSOR;
            case NORTH_WEST_RESIZE:
                return Cursor.NW_RESIZE_CURSOR;
            case SOUTH_RESIZE:
                return Cursor.S_RESIZE_CURSOR;
            case SOUTH_EAST_RESIZE:
                return Cursor.SE_RESIZE_CURSOR;
            case SOUTH_WEST_RESIZE:
                return Cursor.SW_RESIZE_CURSOR;
            case WEST_RESIZE:
                return Cursor.W_RESIZE_CURSOR;
            case MOVE:
                return Cursor.MOVE_CURSOR;
            default:
                return Cursor.DEFAULT_CURSOR;
        }
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
