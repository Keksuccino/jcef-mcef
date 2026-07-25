// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefColor;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefBrowser_N;
import org.cef.input.CefCompositionUnderline;
import org.cef.input.CefCompositionUnderlineStyle;
import org.cef.misc.CefRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;

class CefImeCompositionContractTest {
    private static final CefColor FOREGROUND = CefColor.fromArgb(0xFE123456);
    private static final CefColor BACKGROUND = CefColor.fromArgb(0x01765432);

    @Test
    void publicApiRemainsBinaryCompatibleDefaults() throws Exception {
        assertDefaultVoidMethod(CefBrowser.class.getMethod("imeSetComposition", String.class, List.class, CefRange.class, CefRange.class), "(Ljava/lang/String;Ljava/util/List;Lorg/cef/misc/CefRange;Lorg/cef/misc/CefRange;)V");
        assertDefaultVoidMethod(CefBrowser.class.getMethod("imeCommitText", String.class, CefRange.class, int.class), "(Ljava/lang/String;Lorg/cef/misc/CefRange;I)V");
        assertDefaultVoidMethod(CefBrowser.class.getMethod("imeFinishComposingText", boolean.class), "(Z)V");
        assertDefaultVoidMethod(CefBrowser.class.getMethod("imeCancelComposition"), "()V");
    }

    @Test
    void nativeDeclarationsUseAtomicArraySnapshotsAndExactDescriptors() throws Exception {
        assertNativeVoidMethod(CefBrowser_N.class.getDeclaredMethod("N_ImeSetComposition", String.class, CefCompositionUnderline[].class, CefRange.class, CefRange.class), "(Ljava/lang/String;[Lorg/cef/input/CefCompositionUnderline;Lorg/cef/misc/CefRange;Lorg/cef/misc/CefRange;)V");
        assertNativeVoidMethod(CefBrowser_N.class.getDeclaredMethod("N_ImeCommitText", String.class, CefRange.class, int.class), "(Ljava/lang/String;Lorg/cef/misc/CefRange;I)V");
        assertNativeVoidMethod(CefBrowser_N.class.getDeclaredMethod("N_ImeFinishComposingText", boolean.class), "(Z)V");
        assertNativeVoidMethod(CefBrowser_N.class.getDeclaredMethod("N_ImeCancelComposition"), "()V");

        Method converter = CefBrowser_N.class.getDeclaredMethod("N_ConvertImeCompositionForTesting", String.class, CefCompositionUnderline[].class, CefRange.class, CefRange.class);
        assertTrue(Modifier.isPrivate(converter.getModifiers()));
        assertTrue(Modifier.isStatic(converter.getModifiers()));
        assertTrue(Modifier.isNative(converter.getModifiers()));
        assertEquals("(Ljava/lang/String;[Lorg/cef/input/CefCompositionUnderline;Lorg/cef/misc/CefRange;Lorg/cef/misc/CefRange;)[Ljava/lang/Object;", MethodType.methodType(converter.getReturnType(), converter.getParameterTypes()).toMethodDescriptorString());
    }

    @Test
    void rangePreservesTheCompleteUnsignedDomainAndDirection() {
        CefRange full = new CefRange(0, CefRange.MAX_VALUE);
        CefRange reversed = new CefRange(CefRange.MAX_VALUE, 0);
        CefRange equalInvalid = new CefRange(CefRange.MAX_VALUE, CefRange.MAX_VALUE);

        assertEquals(0, full.getFrom());
        assertEquals(CefRange.MAX_VALUE, full.getTo());
        assertTrue(full.isValid());
        assertFalse(full.isReversed());
        assertTrue(reversed.isValid());
        assertTrue(reversed.isReversed());
        assertEquals(0, reversed.getMinimum());
        assertEquals(CefRange.MAX_VALUE, reversed.getMaximum());
        assertFalse(CefRange.INVALID.isValid());
        assertTrue(CefRange.INVALID.isEmpty());
        assertEquals(CefRange.INVALID, equalInvalid);
        assertEquals(CefRange.INVALID.hashCode(), equalInvalid.hashCode());
        assertNotEquals(CefRange.INVALID, full);
        assertEquals("CefRange{from=4294967295, to=0}", reversed.toString());
        assertThrows(IllegalArgumentException.class, () -> new CefRange(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new CefRange(0, CefRange.MAX_VALUE + 1));
    }

    @Test
    void underlineAndStyleAreImmutableExactCefValues() {
        assertEquals(0, CefCompositionUnderlineStyle.SOLID.getValue());
        assertEquals(1, CefCompositionUnderlineStyle.DOT.getValue());
        assertEquals(2, CefCompositionUnderlineStyle.DASH.getValue());
        assertEquals(3, CefCompositionUnderlineStyle.NONE.getValue());
        for (CefCompositionUnderlineStyle style : CefCompositionUnderlineStyle.values()) assertSame(style, CefCompositionUnderlineStyle.fromValue(style.getValue()));
        assertThrows(IllegalArgumentException.class, () -> CefCompositionUnderlineStyle.fromValue(-1));
        assertThrows(IllegalArgumentException.class, () -> CefCompositionUnderlineStyle.fromValue(4));

        CefCompositionUnderline underline = underline(new CefRange(1, 3), true, CefCompositionUnderlineStyle.DASH);
        CefCompositionUnderline equalUnderline = underline(new CefRange(1, 3), true, CefCompositionUnderlineStyle.DASH);
        assertEquals(new CefRange(1, 3), underline.getRange());
        assertEquals(0xFE123456, underline.getColor().getArgb());
        assertEquals(0x01765432, underline.getBackgroundColor().getArgb());
        assertTrue(underline.isThick());
        assertSame(CefCompositionUnderlineStyle.DASH, underline.getStyle());
        assertEquals(underline, equalUnderline);
        assertEquals(underline.hashCode(), equalUnderline.hashCode());
        assertTrue(underline.toString().contains("style=DASH"));
        assertFinalValueClass(CefRange.class);
        assertFinalValueClass(CefCompositionUnderline.class);
        assertThrows(NullPointerException.class, () -> new CefCompositionUnderline(null, FOREGROUND, BACKGROUND, false, CefCompositionUnderlineStyle.SOLID));
        assertThrows(NullPointerException.class, () -> new CefCompositionUnderline(new CefRange(0, 0), null, BACKGROUND, false, CefCompositionUnderlineStyle.SOLID));
        assertThrows(NullPointerException.class, () -> new CefCompositionUnderline(new CefRange(0, 0), FOREGROUND, null, false, CefCompositionUnderlineStyle.SOLID));
        assertThrows(NullPointerException.class, () -> new CefCompositionUnderline(new CefRange(0, 0), FOREGROUND, BACKGROUND, false, null));
    }

    @Test
    void unsupportedImplementationsValidateTopLevelArgumentsBeforeFailingExplicitly() {
        CefBrowser browser = compatibilityBrowser();
        CefCompositionUnderline underline = underline(new CefRange(0, 1), false, CefCompositionUnderlineStyle.SOLID);

        assertUnsupported(() -> browser.imeSetComposition("x", List.of(underline), CefRange.INVALID, new CefRange(1, 1)), "imeSetComposition is not supported by this browser");
        assertUnsupported(() -> browser.imeCommitText("x", CefRange.INVALID, 0), "imeCommitText is not supported by this browser");
        assertUnsupported(() -> browser.imeFinishComposingText(false), "imeFinishComposingText is not supported by this browser");
        assertUnsupported(browser::imeCancelComposition, "imeCancelComposition is not supported by this browser");
        assertNullArgument("text", () -> browser.imeSetComposition(null, List.of(), CefRange.INVALID, CefRange.INVALID));
        assertNullArgument("underlines", () -> browser.imeSetComposition("", null, CefRange.INVALID, CefRange.INVALID));
        assertNullArgument("replacementRange", () -> browser.imeSetComposition("", List.of(), null, CefRange.INVALID));
        assertNullArgument("selectionRange", () -> browser.imeSetComposition("", List.of(), CefRange.INVALID, null));
        assertNullArgument("text", () -> browser.imeCommitText(null, CefRange.INVALID, 0));
        assertNullArgument("replacementRange", () -> browser.imeCommitText("", null, 0));
    }

    @Test
    void nativeWrappersValidateBeforeLifecycleAndPreserveCefRangeSemantics() {
        CefBrowserOsr browser = new CefBrowserOsr(null, "about:blank", false, null);
        CefCompositionUnderline emoji = underline(new CefRange(1, 3), true, CefCompositionUnderlineStyle.DOT);

        assertDoesNotThrow(() -> browser.imeSetComposition("A😀B", List.of(emoji), new CefRange(CefRange.MAX_VALUE, 0), new CefRange(0, CefRange.MAX_VALUE)));
        assertDoesNotThrow(() -> browser.imeSetComposition("A😀B", Arrays.asList(underline(new CefRange(3, 4), false, CefCompositionUnderlineStyle.NONE), underline(new CefRange(0, 0), false, CefCompositionUnderlineStyle.SOLID), underline(new CefRange(1, 3), true, CefCompositionUnderlineStyle.DASH)), CefRange.INVALID, new CefRange(7, 2)));
        assertDoesNotThrow(() -> browser.imeSetComposition("A\0\uD800B", List.of(), new CefRange(99, 2), new CefRange(CefRange.MAX_VALUE, 0)));
        assertDoesNotThrow(() -> browser.imeCommitText("A\0\uD800😀B", new CefRange(CefRange.MAX_VALUE, 0), Integer.MIN_VALUE));
        assertDoesNotThrow(() -> browser.imeCommitText("", new CefRange(0, CefRange.MAX_VALUE), Integer.MAX_VALUE));
        assertDoesNotThrow(() -> browser.imeFinishComposingText(true));
        assertDoesNotThrow(browser::imeCancelComposition);

        assertNullArgument("text", () -> browser.imeSetComposition(null, List.of(), CefRange.INVALID, CefRange.INVALID));
        assertNullArgument("underlines", () -> browser.imeSetComposition("", null, CefRange.INVALID, CefRange.INVALID));
        assertNullArgument("replacementRange", () -> browser.imeSetComposition("", List.of(), null, CefRange.INVALID));
        assertNullArgument("selectionRange", () -> browser.imeSetComposition("", List.of(), CefRange.INVALID, null));
        assertNullArgument("underlines[1]", () -> browser.imeSetComposition("x", Arrays.asList(underline(new CefRange(0, 1), false, CefCompositionUnderlineStyle.SOLID), null), CefRange.INVALID, CefRange.INVALID));
        assertThrows(IllegalArgumentException.class, () -> browser.imeSetComposition("x", List.of(underline(CefRange.INVALID, false, CefCompositionUnderlineStyle.SOLID)), CefRange.INVALID, CefRange.INVALID));
        assertThrows(IllegalArgumentException.class, () -> browser.imeSetComposition("xy", List.of(underline(new CefRange(2, 1), false, CefCompositionUnderlineStyle.SOLID)), CefRange.INVALID, CefRange.INVALID));
        assertThrows(IllegalArgumentException.class, () -> browser.imeSetComposition("A😀B", List.of(underline(new CefRange(1, 5), false, CefCompositionUnderlineStyle.SOLID)), CefRange.INVALID, CefRange.INVALID));
        assertNullArgument("text", () -> browser.imeCommitText(null, CefRange.INVALID, 0));
        assertNullArgument("replacementRange", () -> browser.imeCommitText("", null, 0));

        browser.onBeforeClose();
        assertNullArgument("text", () -> browser.imeSetComposition(null, List.of(), CefRange.INVALID, CefRange.INVALID));
        assertThrows(IllegalArgumentException.class, () -> browser.imeSetComposition("xy", List.of(underline(new CefRange(2, 1), false, CefCompositionUnderlineStyle.SOLID)), CefRange.INVALID, CefRange.INVALID));
        assertNullArgument("replacementRange", () -> browser.imeCommitText("", null, 0));
        assertDoesNotThrow(() -> browser.imeSetComposition("x", List.of(underline(new CefRange(0, 1), false, CefCompositionUnderlineStyle.SOLID)), CefRange.INVALID, new CefRange(1, 1)));
        assertDoesNotThrow(() -> browser.imeCommitText("x", CefRange.INVALID, 0));
        assertDoesNotThrow(() -> browser.imeFinishComposingText(false));
        assertDoesNotThrow(browser::imeCancelComposition);
    }

    private static CefCompositionUnderline underline(CefRange range, boolean thick, CefCompositionUnderlineStyle style) {
        return new CefCompositionUnderline(range, FOREGROUND, BACKGROUND, thick, style);
    }

    private static void assertDefaultVoidMethod(Method method, String descriptor) {
        assertTrue(method.isDefault());
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertFalse(Modifier.isAbstract(method.getModifiers()));
        assertEquals(void.class, method.getReturnType());
        assertEquals(descriptor, MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString());
    }

    private static void assertNativeVoidMethod(Method method, String descriptor) {
        int modifiers = method.getModifiers();
        assertTrue(Modifier.isPrivate(modifiers));
        assertTrue(Modifier.isFinal(modifiers));
        assertTrue(Modifier.isNative(modifiers));
        assertFalse(Modifier.isStatic(modifiers));
        assertEquals(void.class, method.getReturnType());
        assertEquals(descriptor, MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString());
    }

    private static void assertFinalValueClass(Class<?> type) {
        assertTrue(Modifier.isFinal(type.getModifiers()));
        for (Field field : type.getDeclaredFields()) {
            if (!field.isSynthetic()) assertTrue(Modifier.isFinal(field.getModifiers()), () -> type.getSimpleName() + "." + field.getName() + " is mutable");
        }
    }

    private static CefBrowser compatibilityBrowser() {
        InvocationHandler handler = (proxy, method, arguments) -> {
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, arguments);
            throw new UnsupportedOperationException(method.getName());
        };
        return (CefBrowser) Proxy.newProxyInstance(CefBrowser.class.getClassLoader(), new Class<?>[] {CefBrowser.class}, handler);
    }

    private static void assertUnsupported(Executable executable, String message) {
        UnsupportedOperationException exception = assertThrows(UnsupportedOperationException.class, executable);
        assertEquals(message, exception.getMessage());
    }

    private static void assertNullArgument(String argument, Executable executable) {
        NullPointerException exception = assertThrows(NullPointerException.class, executable);
        assertEquals(argument, exception.getMessage());
    }
}
