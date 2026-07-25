// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefColor;
import org.cef.browser.CefBrowser_N;
import org.cef.input.CefCompositionUnderline;
import org.cef.input.CefCompositionUnderlineStyle;
import org.cef.misc.CefRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

@NativeCefTest
class CefImeCompositionConversionTest {
    private static final int HEADER_FIELDS = 6;
    private static final int UNDERLINE_FIELDS = 7;
    private static final Method CONVERT = conversionMethod();

    @Test
    void convertsEveryFieldWithoutLosingUtf16OrUnsignedBits() {
        String text = "A\0\uD800😀B";
        CefCompositionUnderline[] underlines = {
                underline(0, 0, 0x00000000, 0xFFFFFFFF, false, CefCompositionUnderlineStyle.SOLID),
                underline(0, 2, 0x80123456, 0x01765432, true, CefCompositionUnderlineStyle.DOT),
                underline(2, 3, 0xFEDCBA98, 0x7F010203, false, CefCompositionUnderlineStyle.DASH),
                underline(3, 5, 0xFFFFFFFF, 0x00000000, true, CefCompositionUnderlineStyle.NONE),
        };
        CefRange replacement = new CefRange(CefRange.MAX_VALUE, 0);
        CefRange selection = new CefRange(0, CefRange.MAX_VALUE);

        Object[] snapshot = convert(text, underlines, replacement, selection);
        assertEquals(text, snapshot[0]);
        long[] values = (long[]) snapshot[1];
        assertEquals(HEADER_FIELDS + underlines.length * UNDERLINE_FIELDS, values.length);
        assertArrayEquals(new long[] {CefRange.MAX_VALUE, 0, 0, CefRange.MAX_VALUE}, new long[] {values[0], values[1], values[2], values[3]});
        assertTrue(values[4] > 0, "CEF composition underline size was not initialized");
        assertEquals(underlines.length, values[5]);

        for (int index = 0; index < underlines.length; index++) {
            CefCompositionUnderline underline = underlines[index];
            int offset = HEADER_FIELDS + index * UNDERLINE_FIELDS;
            assertEquals(values[4], values[offset], "Each converted structure must advertise the native ABI size");
            assertEquals(underline.getRange().getFrom(), values[offset + 1]);
            assertEquals(underline.getRange().getTo(), values[offset + 2]);
            assertEquals(Integer.toUnsignedLong(underline.getColor().getArgb()), values[offset + 3]);
            assertEquals(Integer.toUnsignedLong(underline.getBackgroundColor().getArgb()), values[offset + 4]);
            assertEquals(underline.isThick() ? 1 : 0, values[offset + 5]);
            assertEquals(underline.getStyle().getValue(), values[offset + 6]);
        }
    }

    @Test
    void acceptsEmptyCompositionDataAndExactInvalidSentinels() {
        Object[] snapshot = convert("", new CefCompositionUnderline[0], CefRange.INVALID, CefRange.INVALID);

        assertEquals("", snapshot[0]);
        assertArrayEquals(new long[] {CefRange.MAX_VALUE, CefRange.MAX_VALUE, CefRange.MAX_VALUE, CefRange.MAX_VALUE, ((long[]) snapshot[1])[4], 0}, (long[]) snapshot[1]);
        assertTrue(((long[]) snapshot[1])[4] > 0);
    }

    @Test
    void nativeRevalidationRejectsNullAndInvalidUnderlineDataAtomically() {
        assertNullArgument("text must not be null", () -> convert(null, new CefCompositionUnderline[0], CefRange.INVALID, CefRange.INVALID));
        assertNullArgument("underlines must not be null", () -> convert("", null, CefRange.INVALID, CefRange.INVALID));
        assertNullArgument("replacementRange must not be null", () -> convert("", new CefCompositionUnderline[0], null, CefRange.INVALID));
        assertNullArgument("selectionRange must not be null", () -> convert("", new CefCompositionUnderline[0], CefRange.INVALID, null));
        assertNullArgument("underlines[0] must not be null", () -> convert("x", new CefCompositionUnderline[] {null}, CefRange.INVALID, CefRange.INVALID));
        assertThrows(IllegalArgumentException.class, () -> convert("x", new CefCompositionUnderline[] {underline(CefRange.INVALID, 0, 0, false, CefCompositionUnderlineStyle.SOLID)}, CefRange.INVALID, CefRange.INVALID));
        assertThrows(IllegalArgumentException.class, () -> convert("xy", new CefCompositionUnderline[] {underline(new CefRange(2, 1), 0, 0, false, CefCompositionUnderlineStyle.SOLID)}, CefRange.INVALID, CefRange.INVALID));
        assertThrows(IllegalArgumentException.class, () -> convert("A😀B", new CefCompositionUnderline[] {underline(new CefRange(1, 5), 0, 0, false, CefCompositionUnderlineStyle.SOLID)}, CefRange.INVALID, CefRange.INVALID));
    }

    private static CefCompositionUnderline underline(long from, long to, int color, int backgroundColor, boolean thick, CefCompositionUnderlineStyle style) {
        return underline(new CefRange(from, to), color, backgroundColor, thick, style);
    }

    private static CefCompositionUnderline underline(CefRange range, int color, int backgroundColor, boolean thick, CefCompositionUnderlineStyle style) {
        return new CefCompositionUnderline(range, CefColor.fromArgb(color), CefColor.fromArgb(backgroundColor), thick, style);
    }

    private static Object[] convert(String text, CefCompositionUnderline[] underlines, CefRange replacement, CefRange selection) {
        try {
            return (Object[]) CONVERT.invoke(null, text, underlines, replacement, selection);
        } catch (IllegalAccessException exception) {
            throw new AssertionError(exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError(cause);
        }
    }

    private static Method conversionMethod() {
        try {
            Method method = CefBrowser_N.class.getDeclaredMethod("N_ConvertImeCompositionForTesting", String.class, CefCompositionUnderline[].class, CefRange.class, CefRange.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void assertNullArgument(String message, Executable executable) {
        NullPointerException exception = assertThrows(NullPointerException.class, executable);
        assertEquals(message, exception.getMessage());
    }
}
