// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

import org.cef.CefBrowserSettings;
import org.cef.CefClient;
import org.cef.callback.CefDragData;
import org.cef.handler.CefRenderHandler;
import org.cef.handler.CefScreenInfo;

import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * This class represents a headless off-screen rendered browser. MCEF subclasses
 * this class to provide rendering and input without depending on AWT or JOGL UI
 * components.
 */
public class CefBrowserOsr extends CefBrowser_N implements CefRenderHandler {
    private boolean justCreated_ = false;
    // Kept mutable and protected for source compatibility with MCEF subclasses that own sizing.
    protected Rectangle browser_rect_ = new Rectangle(0, 0, 1, 1); // Work around CEF issue #1437.
    private Point screenPoint_ = new Point(0, 0);
    private double scaleFactor_ = 1.0;
    private int depth_ = 32;
    private int depthPerComponent_ = 8;
    private final boolean isTransparent_;
    private final CopyOnWriteArrayList<Consumer<CefPaintEvent>> onPaintListeners =
            new CopyOnWriteArrayList<Consumer<CefPaintEvent>>();

    public CefBrowserOsr(
            CefClient client, String url, boolean transparent, CefRequestContext context) {
        this(client, url, transparent, context, null);
    }

    public CefBrowserOsr(CefClient client, String url, boolean transparent,
            CefRequestContext context, CefBrowserSettings settings) {
        this(client, url, transparent, context, null, null, settings);
    }

    protected CefBrowserOsr(CefClient client, String url, boolean transparent,
            CefRequestContext context, CefBrowserOsr parent, Point inspectAt,
            CefBrowserSettings settings) {
        super(client, url, context, parent, inspectAt, settings);
        isTransparent_ = transparent;
    }

    @Override
    public void createImmediately() {
        justCreated_ = true;
        createBrowserIfRequired(false);
    }

    /**
     * MCEF owns the display surface, so this headless implementation deliberately
     * has no AWT component. Windowed browsers still expose the upstream component
     * through {@link CefBrowserWr}.
     */
    @Override
    public Component getUIComponent() {
        return null;
    }

    @Override
    public CefRenderHandler getRenderHandler() {
        return this;
    }

    @Override
    protected CefBrowser_N createDevToolsBrowser(CefClient client, String url,
            CefRequestContext context, CefBrowser_N parent, Point inspectAt) {
        return new CefBrowserOsr(
                client, url, isTransparent_, context, (CefBrowserOsr) parent, inspectAt, null);
    }

    @Override
    public Rectangle getViewRect(CefBrowser browser) {
        return browser_rect_;
    }

    @Override
    public Point getScreenPoint(CefBrowser browser, Point viewPoint) {
        Point screenPoint = new Point(screenPoint_);
        screenPoint.translate(viewPoint.x, viewPoint.y);
        return screenPoint;
    }

    @Override
    public void onPopupShow(CefBrowser browser, boolean show) {}

    @Override
    public void onPopupSize(CefBrowser browser, Rectangle size) {}

    @Override
    public void addOnPaintListener(Consumer<CefPaintEvent> listener) {
        onPaintListeners.add(listener);
    }

    @Override
    public void setOnPaintListener(Consumer<CefPaintEvent> listener) {
        onPaintListeners.clear();
        onPaintListeners.add(listener);
    }

    @Override
    public void removeOnPaintListener(Consumer<CefPaintEvent> listener) {
        onPaintListeners.remove(listener);
    }

    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects,
            ByteBuffer buffer, int width, int height) {
        notifyPaintListeners(browser, popup, dirtyRects, buffer, width, height);
    }

    protected final void notifyPaintListeners(CefBrowser browser, boolean popup,
            Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
        if (onPaintListeners.isEmpty()) return;

        CefPaintEvent paintEvent =
                new CefPaintEvent(browser, popup, dirtyRects, buffer, width, height);
        for (Consumer<CefPaintEvent> listener : onPaintListeners) {
            listener.accept(paintEvent);
        }
    }

    @Override
    public boolean onCursorChange(CefBrowser browser, final int cursorType) {
        return true;
    }

    @Override
    public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y) {
        // CefClient transfers an owned clone to the per-browser handler. This default headless
        // implementation does not retain it, while MCEF subclasses retain and dispose it when the
        // emulated drag ends.
        dragData.dispose();
        return false;
    }

    @Override
    public void updateDragCursor(CefBrowser browser, int operation) {}

    protected final void createBrowserIfRequired(boolean hasParent) {
        long windowHandle = getWindowHandleForCreate(hasParent);
        if (getNativeRef("CefBrowser") == 0) {
            if (getParentBrowser() != null) {
                createDevTools(getParentBrowser(), getClient(), windowHandle, true, isTransparent_,
                        null, getInspectAt());
            } else {
                createBrowser(getClient(), windowHandle, getUrl(), true, isTransparent_, null,
                        getRequestContext());
            }
        } else if (hasParent && justCreated_) {
            notifyAfterParentChanged();
            setFocus(true);
            justCreated_ = false;
        }
    }

    protected long getWindowHandleForCreate(boolean hasParent) {
        return 0;
    }

    protected final boolean isTransparent() {
        return isTransparent_;
    }

    protected final void updateViewGeometry(
            int x, int y, int width, int height, Point screenPoint) {
        browser_rect_.setBounds(x, y, width, height);
        screenPoint_ = new Point(screenPoint);
    }

    protected final void updateScreenInfo(double scaleFactor, int depth, int depthPerComponent) {
        scaleFactor_ = scaleFactor;
        depth_ = depth;
        depthPerComponent_ = depthPerComponent;
    }

    protected final double getDeviceScaleFactor() {
        return scaleFactor_;
    }

    protected final void notifyAfterParentChanged() {
        // OSR has no native window to reparent, but clients still rely on this lifecycle event.
        getClient().onAfterParentChanged(this);
    }

    @Override
    public boolean getScreenInfo(CefBrowser browser, CefScreenInfo screenInfo) {
        screenInfo.Set(scaleFactor_, depth_, depthPerComponent_, false, browser_rect_.getBounds(),
                browser_rect_.getBounds());
        return true;
    }

    @Override
    public CompletableFuture<BufferedImage> createScreenshot(boolean nativeResolution) {
        throw new UnsupportedOperationException(
                "Headless OSR screenshot capture requires an application-provided implementation");
    }
}
