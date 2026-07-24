// Copyright (c) 2024 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef;

/**
 * Browser initialization settings. Specify {@code null} or leave fields at their default values to
 * get the recommended CEF values. The consequences of using custom values may not be well tested.
 * Many of these and other settings can also be configured using command-line switches.
 */
public class CefBrowserSettings {
    /**
     * The maximum rate in frames per second (fps) that {@code CefRenderHandler.onPaint} will be
     * called for a windowless browser. The actual fps may be lower if the browser cannot generate
     * frames at the requested rate. The minimum non-default value is 1 and the CEF default is 30;
     * leave this value at 0 to use that default. This value can also be changed dynamically via
     * {@code CefBrowser.setWindowlessFrameRate}.
     */
    public int windowless_frame_rate = 0;

    /**
     * Font family used for text that does not specify a CSS generic family. {@code null} or an
     * empty string uses the platform's recommended default.
     */
    public String standard_font_family = null;

    /**
     * Font family used for the CSS {@code monospace} generic family. {@code null} or an empty
     * string uses the platform's recommended default.
     */
    public String fixed_font_family = null;

    /**
     * Font family used for the CSS {@code serif} generic family. {@code null} or an empty string
     * uses the platform's recommended default.
     */
    public String serif_font_family = null;

    /**
     * Font family used for the CSS {@code sans-serif} generic family. {@code null} or an empty
     * string uses the platform's recommended default.
     */
    public String sans_serif_font_family = null;

    /**
     * Font family used for the CSS {@code cursive} generic family. {@code null} or an empty string
     * uses the platform's recommended default.
     */
    public String cursive_font_family = null;

    /**
     * Font family used for the CSS {@code fantasy} generic family. {@code null} or an empty string
     * uses the platform's recommended default.
     */
    public String fantasy_font_family = null;

    /** Default proportional font size in pixels. A value of 0 uses the CEF default. */
    public int default_font_size = 0;

    /** Default fixed-width font size in pixels. A value of 0 uses the CEF default. */
    public int default_fixed_font_size = 0;

    /** Minimum font size in pixels. A value of 0 uses the CEF default. */
    public int minimum_font_size = 0;

    /** Minimum logical font size in pixels. A value of 0 uses the CEF default. */
    public int minimum_logical_font_size = 0;

    /**
     * Default encoding for Web content. If {@code null} or empty, {@code "ISO-8859-1"} will be
     * used. Also configurable using the {@code "default-encoding"} command-line switch.
     */
    public String default_encoding = null;

    /**
     * Controls the loading of fonts from remote sources. Also configurable using the
     * {@code "disable-remote-fonts"} command-line switch. The default state delegates to CEF.
     */
    public CefState remote_fonts = CefState.DEFAULT;

    /**
     * Controls whether JavaScript can be executed. Also configurable using the
     * {@code "disable-javascript"} command-line switch. The default state delegates to CEF.
     */
    public CefState javascript = CefState.DEFAULT;

    /**
     * Controls whether JavaScript can be used to close windows that were not opened via
     * JavaScript. JavaScript can still close windows that were opened via JavaScript or have no
     * back/forward history. Also configurable using the
     * {@code "disable-javascript-close-windows"} command-line switch. The default state delegates
     * to CEF.
     */
    public CefState javascript_close_windows = CefState.DEFAULT;

    /**
     * Controls whether JavaScript can access the clipboard. Also configurable using the
     * {@code "disable-javascript-access-clipboard"} command-line switch. The default state
     * delegates to CEF.
     */
    public CefState javascript_access_clipboard = CefState.DEFAULT;

    /**
     * Controls whether DOM pasting is supported in the editor via {@code execCommand("paste")}.
     * {@link #javascript_access_clipboard} must also be enabled. Also configurable using the
     * {@code "disable-javascript-dom-paste"} command-line switch. The default state delegates to
     * CEF.
     */
    public CefState javascript_dom_paste = CefState.DEFAULT;

    /**
     * Controls whether image URLs will be loaded from the network. A cached image will still be
     * rendered if requested. Also configurable using the {@code "disable-image-loading"}
     * command-line switch. The default state delegates to CEF.
     */
    public CefState image_loading = CefState.DEFAULT;

    /**
     * Controls whether standalone images will be shrunk to fit the page. Also configurable using
     * the {@code "image-shrink-standalone-to-fit"} command-line switch. The default state delegates
     * to CEF.
     */
    public CefState image_shrink_standalone_to_fit = CefState.DEFAULT;

    /**
     * Controls whether text areas can be resized. Also configurable using the
     * {@code "disable-text-area-resize"} command-line switch. The default state delegates to CEF.
     */
    public CefState text_area_resize = CefState.DEFAULT;

    /**
     * Controls whether the Tab key can advance focus to links. Also configurable using the
     * {@code "disable-tab-to-links"} command-line switch. The default state delegates to CEF.
     */
    public CefState tab_to_links = CefState.DEFAULT;

    /**
     * Controls whether local storage can be used. Also configurable using the
     * {@code "disable-local-storage"} command-line switch. The default state delegates to CEF.
     */
    public CefState local_storage = CefState.DEFAULT;

    /**
     * Controls whether WebGL can be used. WebGL requires hardware support and may not work on all
     * systems even when enabled. Also configurable using the {@code "disable-webgl"} command-line
     * switch. The default state delegates to CEF.
     */
    public CefState webgl = CefState.DEFAULT;

    /**
     * Background color used before a document is loaded and when no document color is specified.
     * Leave this value {@code null} to derive the background from the browser's transparency
     * option. For an explicit value the alpha component must be fully opaque (0xFF) or fully
     * transparent (0x00). Fully opaque alpha uses the RGB components. Fully transparent alpha uses
     * {@link CefSettings#background_color} for a windowed browser and enables transparent painting
     * for a transparent windowless browser.
     */
    public CefColor background_color = null;

    /**
     * Controls whether the Chrome status bubble will be used. Only supported with Chrome style.
     * See <a href="https://www.chromium.org/user-experience/status-bubble/">the Chromium status
     * bubble documentation</a> for details. The default state delegates to CEF.
     */
    public CefState chrome_status_bubble = CefState.DEFAULT;

    /**
     * Controls whether the Chrome zoom bubble will be shown when zooming. Only supported with
     * Chrome style. The default state delegates to CEF.
     */
    public CefState chrome_zoom_bubble = CefState.DEFAULT;

    /** Creates browser settings initialized to CEF's recommended defaults. */
    public CefBrowserSettings() {}

    /**
     * Validates settings that have an explicit Java/CEF creation contract. Font size values are
     * intentionally left to CEF because CEF does not document Java-side validation ranges for
     * them.
     *
     * @param isOffscreenRendered whether the browser will use windowless rendering
     * @param isTransparent whether a windowless browser will use transparent painting; ignored
     *        for windowed browsers because fully transparent alpha selects the global background
     *        color instead of transparent painting
     * @throws IllegalArgumentException if a state is null, the frame rate is negative, or an
     *         explicit background color is incompatible with {@code isTransparent}
     */
    public void validate(boolean isOffscreenRendered, boolean isTransparent) {
        if (windowless_frame_rate < 0) {
            throw new IllegalArgumentException("windowless_frame_rate must be 0 or greater: " + windowless_frame_rate);
        }

        requireState("remote_fonts", remote_fonts);
        requireState("javascript", javascript);
        requireState("javascript_close_windows", javascript_close_windows);
        requireState("javascript_access_clipboard", javascript_access_clipboard);
        requireState("javascript_dom_paste", javascript_dom_paste);
        requireState("image_loading", image_loading);
        requireState("image_shrink_standalone_to_fit", image_shrink_standalone_to_fit);
        requireState("text_area_resize", text_area_resize);
        requireState("tab_to_links", tab_to_links);
        requireState("local_storage", local_storage);
        requireState("webgl", webgl);
        requireState("chrome_status_bubble", chrome_status_bubble);
        requireState("chrome_zoom_bubble", chrome_zoom_bubble);

        if (background_color == null) return;

        int alpha = background_color.getAlpha();
        if (alpha != 0 && alpha != 255) {
            throw new IllegalArgumentException("background_color alpha must be fully transparent (0) or fully opaque (255): " + alpha);
        }
        if (isOffscreenRendered && (alpha == 0) != isTransparent) {
            throw new IllegalArgumentException("background_color alpha must be 0 when isTransparent is true and 255 when isTransparent is false");
        }
    }

    private static void requireState(String fieldName, CefState state) {
        if (state == null) throw new IllegalArgumentException(fieldName + " must not be null");
    }

    /**
     * Returns an independent settings container. String, enum and color references are shared
     * safely because all are immutable.
     */
    @Override
    public CefBrowserSettings clone() {
        CefBrowserSettings copy = new CefBrowserSettings();
        copy.windowless_frame_rate = windowless_frame_rate;
        copy.standard_font_family = standard_font_family;
        copy.fixed_font_family = fixed_font_family;
        copy.serif_font_family = serif_font_family;
        copy.sans_serif_font_family = sans_serif_font_family;
        copy.cursive_font_family = cursive_font_family;
        copy.fantasy_font_family = fantasy_font_family;
        copy.default_font_size = default_font_size;
        copy.default_fixed_font_size = default_fixed_font_size;
        copy.minimum_font_size = minimum_font_size;
        copy.minimum_logical_font_size = minimum_logical_font_size;
        copy.default_encoding = default_encoding;
        copy.remote_fonts = remote_fonts;
        copy.javascript = javascript;
        copy.javascript_close_windows = javascript_close_windows;
        copy.javascript_access_clipboard = javascript_access_clipboard;
        copy.javascript_dom_paste = javascript_dom_paste;
        copy.image_loading = image_loading;
        copy.image_shrink_standalone_to_fit = image_shrink_standalone_to_fit;
        copy.text_area_resize = text_area_resize;
        copy.tab_to_links = tab_to_links;
        copy.local_storage = local_storage;
        copy.webgl = webgl;
        copy.background_color = background_color;
        copy.chrome_status_bubble = chrome_status_bubble;
        copy.chrome_zoom_bubble = chrome_zoom_bubble;
        return copy;
    }
}
