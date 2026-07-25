// Copyright (c) 2024 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

import java.awt.Rectangle;
import java.nio.ByteBuffer;

/**
 * Describes one synchronous off-screen paint callback.
 *
 * <p>The dirty rectangles are detached snapshots. The rendered frame is an independent read-only
 * view of CEF-owned memory: changing its position, limit, or byte order cannot affect another
 * listener, but the bytes are valid only while the listener callback is running. Call
 * {@link #copyRenderedFrame()} inside the callback when the pixels must be retained.
 */
public class CefPaintEvent {
    private final CefBrowser browser;
    private final boolean popup;
    private final Rectangle[] dirtyRects;
    private final ByteBuffer renderedFrame;
    private final int width;
    private final int height;

    public CefPaintEvent(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer renderedFrame, int width, int height) {
        this.browser = browser;
        this.popup = popup;
        this.dirtyRects = copyDirtyRects(dirtyRects);
        this.renderedFrame = createReadOnlyView(renderedFrame);
        this.width = width;
        this.height = height;
    }

    public CefBrowser getBrowser() {
        return browser;
    }

    public boolean getPopup() {
        return popup;
    }

    public Rectangle[] getDirtyRects() {
        return copyDirtyRects(dirtyRects);
    }

    /**
     * Returns an independent read-only view of the callback-scoped CEF frame.
     *
     * <p>The returned view preserves the original position, limit, directness, and byte order.
     * Its position, limit, and byte order may be changed without affecting this event or another
     * listener. The underlying bytes must not be accessed after the listener callback returns.
     * Use {@link #copyRenderedFrame()} when the pixels need to outlive the callback.
     */
    public ByteBuffer getRenderedFrame() {
        return createReadOnlyView(renderedFrame);
    }

    /**
     * Copies the remaining frame bytes into Java-owned read-only storage.
     *
     * <p>This method reads the borrowed CEF frame and therefore must be called before the listener
     * callback returns. The returned buffer starts at position zero, preserves the source byte
     * order and directness, and remains valid after the callback. Returns {@code null} when this
     * event has no rendered frame.
     */
    public ByteBuffer copyRenderedFrame() {
        ByteBuffer source = createReadOnlyView(renderedFrame);
        if (source == null) return null;
        ByteBuffer copy = source.isDirect() ? ByteBuffer.allocateDirect(source.remaining()) : ByteBuffer.allocate(source.remaining());
        copy.order(source.order());
        copy.put(source);
        copy.flip();
        return createReadOnlyView(copy);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    private static Rectangle[] copyDirtyRects(Rectangle[] source) {
        if (source == null) return null;
        Rectangle[] copy = new Rectangle[source.length];
        for (int index = 0; index < source.length; index++) {
            Rectangle rectangle = source[index];
            copy[index] = rectangle == null ? null : new Rectangle(rectangle);
        }
        return copy;
    }

    private static ByteBuffer createReadOnlyView(ByteBuffer source) {
        if (source == null) return null;
        ByteBuffer view = source.asReadOnlyBuffer();
        view.order(source.order());
        return view;
    }
}
