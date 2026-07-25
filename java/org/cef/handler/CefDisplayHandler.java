// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.handler;

import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.misc.CefCursorInfo;

import java.util.List;

/**
 * Implement this interface to handle events related to browser display state.
 * The methods of this class will be called on the UI thread.
 */
public interface CefDisplayHandler {
    /**
     * Browser address changed.
     * @param browser The browser generating the event.
     * @param frame The frame generating the event.
     * @param url The new address.
     */
    public void onAddressChange(CefBrowser browser, CefFrame frame, String url);

    /**
     * Browser title changed.
     * @param browser The browser generating the event.
     * @param title The new title.
     */
    public void onTitleChange(CefBrowser browser, String title);

    /**
     * Browser page icon URLs changed.
     * @param browser The browser generating the event.
     * @param iconUrls A possibly empty ordered snapshot of the candidate page icon URLs.
     */
    public default void onFaviconURLChange(CefBrowser browser, List<String> iconUrls) {}

    /**
     * Browser fullscreen mode changed.
     * @param browser The browser generating the event.
     * @param fullscreen True if fullscreen mode is on.
     */
    public default void onFullscreenModeChange(CefBrowser browser, boolean fullscreen) {}

    /**
     * About to display a tooltip.
     * @param browser The browser generating the event.
     * @param text Contains the text that will be displayed in the tooltip.
     * @return true to handle the tooltip display yourself.
     */
    public boolean onTooltip(CefBrowser browser, String text);

    /**
     * Received a status message.
     * @param browser The browser generating the event.
     * @param value Contains the text that will be displayed in the status message.
     */
    public void onStatusMessage(CefBrowser browser, String value);

    /**
     * Display a console message.
     * @param browser The browser generating the event.
     * @param level
     * @param message
     * @param source
     * @param line
     * @return true to stop the message from being output to the console.
     */
    public boolean onConsoleMessage(CefBrowser browser, CefSettings.LogSeverity level,
            String message, String source, int line);

    /**
     * Overall page loading progress changed.
     * @param browser The browser generating the event.
     * @param progress The current overall loading progress, ranging from 0.0 to 1.0.
     */
    public default void onLoadingProgressChange(CefBrowser browser, double progress) {}

    /**
     * Handle cursor changes.
     * @param browser The browser generating the event.
     * @param cursorType The raw numeric {@code cef_cursor_type_t} value from CEF. Decode it with
     *        {@link org.cef.misc.CefCursorType#fromId(int)}; it is not an AWT predefined-cursor ID.
     * @return true if the cursor change was handled.
     */
    public boolean onCursorChange(CefBrowser browser, int cursorType);

    /**
     * Handle cursor changes with an owned snapshot of any custom cursor image.
     *
     * <p>The default implementation delegates to the legacy two-argument callback so existing
     * implementations continue to receive the unchanged raw {@code cursorType}. CEF's
     * platform-specific cursor handle is not exposed because its representation is not portable
     * and its lifetime ends when the native callback returns.
     * @param browser The browser generating the event.
     * @param cursorType The raw numeric {@code cef_cursor_type_t} value from CEF.
     * @param customCursorInfo The custom cursor snapshot when {@code cursorType} is
     *        {@link org.cef.misc.CefCursorType#CUSTOM}, or {@code null} for non-custom cursors and
     *        invalid or unavailable native custom-cursor data.
     * @return true if the cursor change was handled.
     */
    public default boolean onCursorChange(CefBrowser browser, int cursorType, CefCursorInfo customCursorInfo) {
        return onCursorChange(browser, cursorType);
    }
}
