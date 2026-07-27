// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.cef.CefBrowserSettings;
import org.cef.CefColor;
import org.cef.CefState;
import org.cef.browser.CefBrowser_N;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

@NativeCefTest
class CefBrowserSettingsNativeTest {
    private static final Method CONVERT = getConversionMethod();

    @Test
    void convertsEveryMappedFieldWithoutLoss() {
        CefBrowserSettings settings = new CefBrowserSettings();
        settings.windowless_frame_rate = 240;
        settings.standard_font_family = "Standard Ω";
        settings.fixed_font_family = "Fixed Ж";
        settings.serif_font_family = "Serif 文";
        settings.sans_serif_font_family = "Sans 한";
        settings.cursive_font_family = "Cursive é";
        settings.fantasy_font_family = "Fantasy 🧙";
        settings.default_font_size = 17;
        settings.default_fixed_font_size = 14;
        settings.minimum_font_size = 3;
        settings.minimum_logical_font_size = 7;
        settings.default_encoding = "UTF-16LE";
        settings.remote_fonts = CefState.ENABLED;
        settings.javascript = CefState.DISABLED;
        settings.javascript_close_windows = CefState.DEFAULT;
        settings.javascript_access_clipboard = CefState.ENABLED;
        settings.javascript_dom_paste = CefState.DISABLED;
        settings.image_loading = CefState.DEFAULT;
        settings.image_shrink_standalone_to_fit = CefState.ENABLED;
        settings.text_area_resize = CefState.DISABLED;
        settings.tab_to_links = CefState.DEFAULT;
        settings.local_storage = CefState.ENABLED;
        settings.webgl = CefState.DISABLED;
        settings.background_color = new CefColor(255, 18, 52, 86);
        settings.chrome_status_bubble = CefState.ENABLED;
        settings.chrome_zoom_bubble = CefState.DISABLED;

        Map<String, Object> snapshot = convert(settings, true, false);

        assertEquals(26, snapshot.size());
        assertEquals(240, snapshot.get("windowless_frame_rate"));
        assertEquals("Standard Ω", snapshot.get("standard_font_family"));
        assertEquals("Fixed Ж", snapshot.get("fixed_font_family"));
        assertEquals("Serif 文", snapshot.get("serif_font_family"));
        assertEquals("Sans 한", snapshot.get("sans_serif_font_family"));
        assertEquals("Cursive é", snapshot.get("cursive_font_family"));
        assertEquals("Fantasy 🧙", snapshot.get("fantasy_font_family"));
        assertEquals(17, snapshot.get("default_font_size"));
        assertEquals(14, snapshot.get("default_fixed_font_size"));
        assertEquals(3, snapshot.get("minimum_font_size"));
        assertEquals(7, snapshot.get("minimum_logical_font_size"));
        assertEquals("UTF-16LE", snapshot.get("default_encoding"));
        assertEquals(CefState.ENABLED.getValue(), snapshot.get("remote_fonts"));
        assertEquals(CefState.DISABLED.getValue(), snapshot.get("javascript"));
        assertEquals(CefState.DEFAULT.getValue(), snapshot.get("javascript_close_windows"));
        assertEquals(CefState.ENABLED.getValue(), snapshot.get("javascript_access_clipboard"));
        assertEquals(CefState.DISABLED.getValue(), snapshot.get("javascript_dom_paste"));
        assertEquals(CefState.DEFAULT.getValue(), snapshot.get("image_loading"));
        assertEquals(CefState.ENABLED.getValue(), snapshot.get("image_shrink_standalone_to_fit"));
        assertEquals(CefState.DISABLED.getValue(), snapshot.get("text_area_resize"));
        assertEquals(CefState.DEFAULT.getValue(), snapshot.get("tab_to_links"));
        assertEquals(CefState.ENABLED.getValue(), snapshot.get("local_storage"));
        assertEquals(CefState.DISABLED.getValue(), snapshot.get("webgl"));
        assertEquals(settings.background_color.getArgb(), snapshot.get("background_color"));
        assertEquals(CefState.ENABLED.getValue(), snapshot.get("chrome_status_bubble"));
        assertEquals(CefState.DISABLED.getValue(), snapshot.get("chrome_zoom_bubble"));
        assertFalse(snapshot.containsKey("databases_deprecated"));
    }

    @Test
    void usesJcefFrameRateDefaultAndResolvesLegacyColors() {
        Map<String, Object> defaults = convert(new CefBrowserSettings(), true, false);

        assertEquals(26, defaults.size());
        assertEquals(CefBrowserSettings.DEFAULT_WINDOWLESS_FRAME_RATE, defaults.get("windowless_frame_rate"));
        assertEquals("", defaults.get("standard_font_family"));
        assertEquals("", defaults.get("default_encoding"));
        assertEquals(CefState.DEFAULT.getValue(), defaults.get("javascript"));
        assertEquals(-1, defaults.get("background_color"));
        assertEquals(defaults, convert(null, true, false));
        assertEquals(defaults, convert(null, false, true));

        Map<String, Object> transparent = convert(null, true, true);
        assertEquals(0, transparent.get("background_color"));
        assertEquals(CefBrowserSettings.DEFAULT_WINDOWLESS_FRAME_RATE, transparent.get("windowless_frame_rate"));
    }

    @Test
    void rejectsInvalidFrameStateAndColorContracts() {
        CefBrowserSettings settings = new CefBrowserSettings();
        settings.windowless_frame_rate = -1;
        CefBrowserSettings invalidFrame = settings;
        assertThrows(IllegalArgumentException.class, () -> convert(invalidFrame, true, false));

        settings = new CefBrowserSettings();
        settings.javascript = null;
        CefBrowserSettings nullState = settings;
        assertThrows(IllegalArgumentException.class, () -> convert(nullState, true, false));

        settings = new CefBrowserSettings();
        settings.background_color = new CefColor(127, 1, 2, 3);
        CefBrowserSettings intermediateAlpha = settings;
        assertThrows(IllegalArgumentException.class, () -> convert(intermediateAlpha, false, false));

        settings = new CefBrowserSettings();
        settings.background_color = new CefColor(0, 1, 2, 3);
        CefBrowserSettings opaqueMismatch = settings;
        assertThrows(IllegalArgumentException.class, () -> convert(opaqueMismatch, true, false));

        settings = new CefBrowserSettings();
        settings.background_color = new CefColor(255, 1, 2, 3);
        CefBrowserSettings transparentMismatch = settings;
        assertThrows(IllegalArgumentException.class, () -> convert(transparentMismatch, true, true));
    }

    @Test
    void acceptsTransparentColorForWindowedAndTransparentOsrBrowsers() {
        CefBrowserSettings settings = new CefBrowserSettings();
        settings.background_color = new CefColor(0, 18, 52, 86);

        assertEquals(settings.background_color.getArgb(), convert(settings, false, false).get("background_color"));
        assertEquals(settings.background_color.getArgb(), convert(settings, false, true).get("background_color"));
        assertEquals(settings.background_color.getArgb(), convert(settings, true, true).get("background_color"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> convert(CefBrowserSettings settings, boolean osr, boolean transparent) {
        try {
            return (Map<String, Object>) CONVERT.invoke(null, settings, Boolean.valueOf(osr), Boolean.valueOf(transparent));
        } catch (IllegalAccessException exception) {
            throw new AssertionError(exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException) throw(RuntimeException) cause;
            if (cause instanceof Error) throw(Error) cause;
            throw new AssertionError(cause);
        }
    }

    private static Method getConversionMethod() {
        try {
            Method method = CefBrowser_N.class.getDeclaredMethod("N_ConvertBrowserSettingsForTesting", CefBrowserSettings.class, boolean.class, boolean.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
