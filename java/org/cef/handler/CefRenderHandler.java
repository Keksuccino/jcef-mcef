// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.handler;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefPaintEvent;
import org.cef.callback.CefDragData;
import org.cef.misc.CefCursorInfo;
import org.cef.misc.CefRange;

import java.awt.Point;
import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * Implement this interface to handle events when window rendering is disabled.
 * The methods of this class will be called on the UI thread.
 */
public interface CefRenderHandler {
    /**
     * Retrieve the view rectangle.
     * @param browser The browser generating the event.
     * @return The view rectangle.
     */
    public Rectangle getViewRect(CefBrowser browser);

    /**
     * Retrieve the screen info.
     * @param browser The browser generating the event.
     * @param screenInfo The screenInfo
     * @return True if this callback was handled.  False to fallback to defaults.
     */
    public boolean getScreenInfo(CefBrowser browser, CefScreenInfo screenInfo);

    /**
     * Retrieve the screen point for the specified view point.
     * @param browser The browser generating the event.
     * @param viewPoint The point in the view.
     * @return The screen point.
     */
    public Point getScreenPoint(CefBrowser browser, Point viewPoint);

    /**
     * Show or hide the popup window.
     * @param browser The browser generating the event.
     * @param show True if the popup window is being shown.
     */
    public void onPopupShow(CefBrowser browser, boolean show);

    /**
     * Size the popup window.
     * @param browser The browser generating the event.
     * @param size Size of the popup window.
     */
    public void onPopupSize(CefBrowser browser, Rectangle size);

    /**
     * Handle painting.
     * @param browser The browser generating the event.
     * @param popup True if painting a popup window.
     * @param dirtyRects Array of dirty regions.
     * @param buffer Pixel buffer for the whole window.
     * @param width Width of the buffer.
     * @param height Height of the buffer.
     */
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects,
            ByteBuffer buffer, int width, int height);

    /**
     * Called synchronously on CEF's UI thread when the IME composition range changes.
     *
     * <p>{@code selectedRange} preserves CEF's exact unsigned range direction without
     * normalization. Its endpoints are UTF-16/text offsets, not Unicode code-point indexes.
     * {@code characterBounds} contains the corresponding character bounds in view coordinates;
     * no device-scale conversion is applied.
     *
     * <p>The range, array, and every rectangle are detached, Java-owned, non-null snapshots that
     * remain usable after this callback returns. An empty native bounds list is represented by a
     * non-null, zero-length {@code Rectangle[]}.
     *
     * @param browser The browser generating the event.
     * @param selectedRange The exact selected composition range.
     * @param characterBounds The character bounds in view coordinates.
     */
    public default void onImeCompositionRangeChanged(CefBrowser browser, CefRange selectedRange, Rectangle[] characterBounds) {}

    /**
     * Called synchronously on CEF's UI thread when the text selection changes.
     *
     * <p>{@code selectedText} is an exact UTF-16 snapshot of the currently selected text, including
     * supplementary characters and embedded NUL code units. {@code selectedRange} preserves CEF's
     * exact unsigned endpoints without normalization; its endpoints are UTF-16 offsets, not Unicode
     * code-point indexes. For a reversed selection, the range retains its direction while the text
     * remains in logical document order.
     *
     * <p>Both values are detached, Java-owned, non-null snapshots that remain usable after this
     * callback returns. Collapsed and cleared selections have empty text; CEF can also supply empty
     * text when it cannot derive a selected-text slice from the native event. An omitted native
     * range is represented by {@code CefRange(0, 0)}. CEF may emit an initial empty selection
     * event, so callers must not rely on a fixed callback count or ordering around page and focus
     * changes.
     *
     * @param browser The browser generating the event.
     * @param selectedText The exact selected text. This is empty for collapsed or cleared
     *         selections, or when CEF cannot supply the selected-text slice; inspect
     *         {@code selectedRange} separately to determine the selection state.
     * @param selectedRange The exact selected character range.
     */
    public default void onTextSelectionChanged(CefBrowser browser, String selectedText, CefRange selectedRange) {}

    /**
     * Add provided listener.
     * @param listener Code that gets executed after a frame was rendered.
     */
    public void addOnPaintListener(Consumer<CefPaintEvent> listener);

    /**
     * Remove existing listeners and replace with provided listener.
     * @param listener Code that gets executed after a frame was rendered.
     */
    public void setOnPaintListener(Consumer<CefPaintEvent> listener);

    /**
     * Remove provided listener.
     * @param listener Code that gets executed after a frame was rendered.
     */
    public void removeOnPaintListener(Consumer<CefPaintEvent> listener);

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

    /**
     * Called when the user starts dragging content in the web view. Contextual
     * information about the dragged content is supplied by dragData.
     * OS APIs that run a system message loop may be used within the
     * StartDragging call.
     *
     * Return false to abort the drag operation. Don't call any of
     * CefBrowser-dragSource*Ended* methods after returning false.
     *
     * Return true to handle the drag operation. Call
     * CefBrowser.dragSourceEndedAt and CefBrowser.ragSourceSystemDragEnded either
     * synchronously or asynchronously to inform the web view that the drag
     * operation has ended.
     *
     * <p>When this callback is delegated by {@code CefClient} to a browser's render handler,
     * {@code dragData} is an owned clone rather than the callback-scoped native wrapper. Ownership
     * transfers to the delegated handler when it returns normally, independently of the returned
     * boolean. The handler must call {@link CefDragData#dispose()} after immediate use or after any
     * retained asynchronous use finishes.
     * @param browser The browser generating the event.
     * @param dragData Contextual information about the dragged content
     * @param mask Describes the allowed operation (none, move, copy, link).
     * @param x Coordinate within CefBrowser
     * @param y Coordinate within CefBrowser
     * @return false to abort the drag operation or true to handle the drag operation.
     */
    public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y);

    /**
     * Called when the web view wants to update the mouse cursor during a
     * drag-and-drop operation.
     *
     * @param browser The browser generating the event.
     * @param operation Describes the allowed operation (none, move, copy, link).
     */
    public void updateDragCursor(CefBrowser browser, int operation);
}
