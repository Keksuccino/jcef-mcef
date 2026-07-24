// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefBrowserSettings;
import org.cef.CefColor;
import org.cef.CefState;
import org.cef.browser.CefBrowserFactory;
import org.cef.browser.CefBrowserOsr;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

class CefBrowserSettingsTest {
    private static final String[] STATE_FIELDS = {"remote_fonts", "javascript",
            "javascript_close_windows", "javascript_access_clipboard", "javascript_dom_paste",
            "image_loading", "image_shrink_standalone_to_fit", "text_area_resize", "tab_to_links",
            "local_storage", "webgl", "chrome_status_bubble", "chrome_zoom_bubble"};

    @Test
    void mapsExactCefStateValues() {
        assertEquals(0, CefState.DEFAULT.getValue());
        assertEquals(1, CefState.ENABLED.getValue());
        assertEquals(2, CefState.DISABLED.getValue());
        assertSame(CefState.DEFAULT, CefState.fromValue(0));
        assertSame(CefState.ENABLED, CefState.fromValue(1));
        assertSame(CefState.DISABLED, CefState.fromValue(2));
    }

    @Test
    void rejectsUnknownCefStateValues() {
        assertThrows(IllegalArgumentException.class, () -> CefState.fromValue(-1));
        assertThrows(IllegalArgumentException.class, () -> CefState.fromValue(3));
        assertThrows(IllegalArgumentException.class, () -> CefState.fromValue(Integer.MIN_VALUE));
        assertThrows(IllegalArgumentException.class, () -> CefState.fromValue(Integer.MAX_VALUE));
    }

    @Test
    void constructsColorsAtComponentBoundaries() {
        CefColor minimum = new CefColor(0, 0, 0, 0);
        CefColor maximum = new CefColor(255, 255, 255, 255);

        assertEquals(0, minimum.getAlpha());
        assertEquals(0, minimum.getRed());
        assertEquals(0, minimum.getGreen());
        assertEquals(0, minimum.getBlue());
        assertEquals(0x00000000, minimum.getArgb());
        assertEquals(255, maximum.getAlpha());
        assertEquals(255, maximum.getRed());
        assertEquals(255, maximum.getGreen());
        assertEquals(255, maximum.getBlue());
        assertEquals(0xFFFFFFFF, maximum.getArgb());
    }

    @Test
    void preservesPackedArgbBitsAndValueSemantics() {
        CefColor color = CefColor.fromArgb(0xFEDCBA98);
        CefColor equalColor = new CefColor(0xFE, 0xDC, 0xBA, 0x98);
        CefColor differentColor = CefColor.fromArgb(0xFEDCBA99);

        assertEquals(0xFEDCBA98, color.getArgb());
        assertEquals(0xFE, color.getAlpha());
        assertEquals(0xDC, color.getRed());
        assertEquals(0xBA, color.getGreen());
        assertEquals(0x98, color.getBlue());
        assertEquals(color, equalColor);
        assertEquals(color.hashCode(), equalColor.hashCode());
        assertNotEquals(color, differentColor);
        assertNotEquals(color, null);
        assertNotEquals(color, "0xFEDCBA98");
        assertEquals("CefColor{argb=0xFEDCBA98}", color.toString());
    }

    @Test
    void rejectsInvalidColorComponents() {
        assertThrows(IllegalArgumentException.class, () -> new CefColor(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CefColor(256, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CefColor(0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CefColor(0, 256, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CefColor(0, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new CefColor(0, 0, 256, 0));
        assertThrows(IllegalArgumentException.class, () -> new CefColor(0, 0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new CefColor(0, 0, 0, 256));
    }

    @Test
    void usesCefDefaultsForEverySetting() {
        CefBrowserSettings settings = new CefBrowserSettings();

        assertEquals(26, CefBrowserSettings.class.getFields().length);
        assertEquals(0, settings.windowless_frame_rate);
        assertNull(settings.standard_font_family);
        assertNull(settings.fixed_font_family);
        assertNull(settings.serif_font_family);
        assertNull(settings.sans_serif_font_family);
        assertNull(settings.cursive_font_family);
        assertNull(settings.fantasy_font_family);
        assertEquals(0, settings.default_font_size);
        assertEquals(0, settings.default_fixed_font_size);
        assertEquals(0, settings.minimum_font_size);
        assertEquals(0, settings.minimum_logical_font_size);
        assertNull(settings.default_encoding);
        assertSame(CefState.DEFAULT, settings.remote_fonts);
        assertSame(CefState.DEFAULT, settings.javascript);
        assertSame(CefState.DEFAULT, settings.javascript_close_windows);
        assertSame(CefState.DEFAULT, settings.javascript_access_clipboard);
        assertSame(CefState.DEFAULT, settings.javascript_dom_paste);
        assertSame(CefState.DEFAULT, settings.image_loading);
        assertSame(CefState.DEFAULT, settings.image_shrink_standalone_to_fit);
        assertSame(CefState.DEFAULT, settings.text_area_resize);
        assertSame(CefState.DEFAULT, settings.tab_to_links);
        assertSame(CefState.DEFAULT, settings.local_storage);
        assertSame(CefState.DEFAULT, settings.webgl);
        assertNull(settings.background_color);
        assertSame(CefState.DEFAULT, settings.chrome_status_bubble);
        assertSame(CefState.DEFAULT, settings.chrome_zoom_bubble);
    }

    @Test
    void cloneCopiesEverySettingIntoAnIndependentContainer() {
        CefBrowserSettings settings = populatedSettings();

        CefBrowserSettings copy = settings.clone();

        assertNotSame(settings, copy);
        assertEquals(settings.windowless_frame_rate, copy.windowless_frame_rate);
        assertSame(settings.standard_font_family, copy.standard_font_family);
        assertSame(settings.fixed_font_family, copy.fixed_font_family);
        assertSame(settings.serif_font_family, copy.serif_font_family);
        assertSame(settings.sans_serif_font_family, copy.sans_serif_font_family);
        assertSame(settings.cursive_font_family, copy.cursive_font_family);
        assertSame(settings.fantasy_font_family, copy.fantasy_font_family);
        assertEquals(settings.default_font_size, copy.default_font_size);
        assertEquals(settings.default_fixed_font_size, copy.default_fixed_font_size);
        assertEquals(settings.minimum_font_size, copy.minimum_font_size);
        assertEquals(settings.minimum_logical_font_size, copy.minimum_logical_font_size);
        assertSame(settings.default_encoding, copy.default_encoding);
        assertSame(settings.remote_fonts, copy.remote_fonts);
        assertSame(settings.javascript, copy.javascript);
        assertSame(settings.javascript_close_windows, copy.javascript_close_windows);
        assertSame(settings.javascript_access_clipboard, copy.javascript_access_clipboard);
        assertSame(settings.javascript_dom_paste, copy.javascript_dom_paste);
        assertSame(settings.image_loading, copy.image_loading);
        assertSame(settings.image_shrink_standalone_to_fit, copy.image_shrink_standalone_to_fit);
        assertSame(settings.text_area_resize, copy.text_area_resize);
        assertSame(settings.tab_to_links, copy.tab_to_links);
        assertSame(settings.local_storage, copy.local_storage);
        assertSame(settings.webgl, copy.webgl);
        assertSame(settings.background_color, copy.background_color);
        assertSame(settings.chrome_status_bubble, copy.chrome_status_bubble);
        assertSame(settings.chrome_zoom_bubble, copy.chrome_zoom_bubble);

        copy.standard_font_family = "changed";
        copy.javascript = CefState.DEFAULT;
        copy.background_color = CefColor.fromArgb(0xFF000000);

        assertEquals("standard", settings.standard_font_family);
        assertSame(CefState.ENABLED, settings.javascript);
        assertEquals(CefColor.fromArgb(0xFF123456), settings.background_color);
    }

    @Test
    void validatesDefaultsAndDocumentedFrameRateDomain() {
        CefBrowserSettings settings = new CefBrowserSettings();

        assertDoesNotThrow(() -> settings.validate(false, false));
        assertDoesNotThrow(() -> settings.validate(true, false));
        assertDoesNotThrow(() -> settings.validate(true, true));

        settings.windowless_frame_rate = 1;
        assertDoesNotThrow(() -> settings.validate(true, false));
        settings.windowless_frame_rate = Integer.MAX_VALUE;
        assertDoesNotThrow(() -> settings.validate(true, false));
        settings.windowless_frame_rate = -1;
        assertThrows(IllegalArgumentException.class, () -> settings.validate(true, false));
    }

    @Test
    void officialBrowserWrappersRejectInvalidSettingsDuringConstruction() {
        CefBrowserSettings settings = new CefBrowserSettings();
        settings.windowless_frame_rate = -1;

        assertThrows(IllegalArgumentException.class, () -> new CefBrowserOsr(null, "about:blank", false, null, settings));
        assertThrows(IllegalArgumentException.class, () -> CefBrowserFactory.create(null, "about:blank", false, false, null, settings));
        assertThrows(IllegalArgumentException.class, () -> CefBrowserFactory.create(null, "about:blank", true, false, null, settings));
    }

    @Test
    void doesNotOvervalidateUndocumentedFontSizeRanges() {
        CefBrowserSettings settings = new CefBrowserSettings();
        settings.default_font_size = -1;
        settings.default_fixed_font_size = Integer.MIN_VALUE;
        settings.minimum_font_size = Integer.MAX_VALUE;
        settings.minimum_logical_font_size = -100;

        assertDoesNotThrow(() -> settings.validate(false, false));
    }

    @Test
    void rejectsNullStateFields() throws ReflectiveOperationException {
        for (String fieldName : STATE_FIELDS) {
            CefBrowserSettings settings = new CefBrowserSettings();
            Field field = CefBrowserSettings.class.getField(fieldName);
            field.set(settings, null);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> settings.validate(false, false));
            assertTrue(exception.getMessage().contains(fieldName));
        }
    }

    @Test
    void validatesBackgroundAlphaAndRenderingMode() {
        CefBrowserSettings settings = new CefBrowserSettings();

        settings.background_color = new CefColor(255, 12, 34, 56);
        assertDoesNotThrow(() -> settings.validate(false, false));
        assertDoesNotThrow(() -> settings.validate(true, false));

        settings.background_color = new CefColor(0, 12, 34, 56);
        assertDoesNotThrow(() -> settings.validate(false, false));
        assertDoesNotThrow(() -> settings.validate(false, true));
        assertDoesNotThrow(() -> settings.validate(true, true));

        settings.background_color = new CefColor(128, 12, 34, 56);
        assertThrows(IllegalArgumentException.class, () -> settings.validate(false, false));
        assertThrows(IllegalArgumentException.class, () -> settings.validate(true, true));
    }

    @Test
    void rejectsWindowlessBackgroundTransparencyMismatch() {
        CefBrowserSettings settings = new CefBrowserSettings();

        settings.background_color = new CefColor(0, 12, 34, 56);
        assertThrows(IllegalArgumentException.class, () -> settings.validate(true, false));

        settings.background_color = new CefColor(255, 12, 34, 56);
        assertThrows(IllegalArgumentException.class, () -> settings.validate(true, true));
    }

    private static CefBrowserSettings populatedSettings() {
        CefBrowserSettings settings = new CefBrowserSettings();
        settings.windowless_frame_rate = 144;
        settings.standard_font_family = new String("standard");
        settings.fixed_font_family = new String("fixed");
        settings.serif_font_family = new String("serif");
        settings.sans_serif_font_family = new String("sans-serif");
        settings.cursive_font_family = new String("cursive");
        settings.fantasy_font_family = new String("fantasy");
        settings.default_font_size = 17;
        settings.default_fixed_font_size = 14;
        settings.minimum_font_size = 2;
        settings.minimum_logical_font_size = 7;
        settings.default_encoding = new String("UTF-8");
        settings.remote_fonts = CefState.DISABLED;
        settings.javascript = CefState.ENABLED;
        settings.javascript_close_windows = CefState.DISABLED;
        settings.javascript_access_clipboard = CefState.ENABLED;
        settings.javascript_dom_paste = CefState.DISABLED;
        settings.image_loading = CefState.ENABLED;
        settings.image_shrink_standalone_to_fit = CefState.DISABLED;
        settings.text_area_resize = CefState.ENABLED;
        settings.tab_to_links = CefState.DISABLED;
        settings.local_storage = CefState.ENABLED;
        settings.webgl = CefState.DISABLED;
        settings.background_color = CefColor.fromArgb(0xFF123456);
        settings.chrome_status_bubble = CefState.ENABLED;
        settings.chrome_zoom_bubble = CefState.DISABLED;
        return settings;
    }
}
