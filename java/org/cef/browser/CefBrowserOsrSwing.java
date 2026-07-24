// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

import com.jogamp.nativewindow.NativeSurface;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLContext;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.awt.GLCanvas;
import com.jogamp.opengl.util.GLBuffers;

import org.cef.CefBrowserSettings;
import org.cef.CefClient;
import org.cef.OS;
import org.cef.callback.CefDragData;

import java.awt.Component;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.datatransfer.StringSelection;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DragGestureEvent;
import java.awt.dnd.DragGestureRecognizer;
import java.awt.dnd.DragSource;
import java.awt.dnd.DragSourceAdapter;
import java.awt.dnd.DragSourceDropEvent;
import java.awt.dnd.DropTarget;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;

/**
 * Upstream-compatible Swing/JOGL surface for OSR browsers created through the generic factory
 * overloads. The public {@link CefBrowserOsr} base remains component-free for MCEF subclasses.
 */
class CefBrowserOsrSwing extends CefBrowserOsr {
    private final CefRenderer renderer_;
    private GLCanvas canvas_;
    private long windowHandle_ = 0;

    CefBrowserOsrSwing(CefClient client, String url, boolean transparent, CefRequestContext context,
            CefBrowserSettings settings) {
        this(client, url, transparent, context, null, null, settings);
    }

    private CefBrowserOsrSwing(CefClient client, String url, boolean transparent,
            CefRequestContext context, CefBrowserOsrSwing parent, Point inspectAt,
            CefBrowserSettings settings) {
        super(client, url, transparent, context, parent, inspectAt, settings);
        renderer_ = new CefRenderer(transparent);
        createGLCanvas();
    }

    @Override
    public Component getUIComponent() {
        return canvas_;
    }

    @Override
    protected CefBrowser_N createDevToolsBrowser(CefClient client, String url,
            CefRequestContext context, CefBrowser_N parent, Point inspectAt) {
        return new CefBrowserOsrSwing(client, url, isTransparent(), context,
                (CefBrowserOsrSwing) parent, inspectAt, null);
    }

    @Override
    protected long getWindowHandleForCreate(boolean hasParent) {
        return hasParent ? getWindowHandle() : 0;
    }

    private synchronized long getWindowHandle() {
        if (windowHandle_ != 0) return windowHandle_;

        NativeSurface surface = canvas_.getNativeSurface();
        if (surface == null) return 0;
        surface.lockSurface();
        try {
            windowHandle_ = getWindowHandle(surface.getSurfaceHandle());
            assert windowHandle_ != 0;
            return windowHandle_;
        } finally {
            surface.unlockSurface();
        }
    }

    @SuppressWarnings("serial")
    private void createGLCanvas() {
        GLProfile profile = GLProfile.getMaxFixedFunc(true);
        canvas_ = new GLCanvas(new GLCapabilities(profile)) {
            private boolean removed_ = true;

            @Override
            public void paint(Graphics graphics) {
                createBrowserIfRequired(true);
                if (graphics instanceof Graphics2D) {
                    Graphics2D graphics2D = (Graphics2D) graphics;
                    GraphicsConfiguration configuration = graphics2D.getDeviceConfiguration();
                    int depth = configuration.getColorModel().getPixelSize();
                    int[] componentSizes = configuration.getColorModel().getComponentSize();
                    int depthPerComponent = componentSizes.length == 0 ? depth : componentSizes[0];
                    updateScreenInfo(
                            graphics2D.getTransform().getScaleX(), depth, depthPerComponent);
                }
                super.paint(graphics);
            }

            @Override
            public void addNotify() {
                super.addNotify();
                if (removed_) {
                    notifyAfterParentChanged();
                    removed_ = false;
                }
            }

            @Override
            public void removeNotify() {
                if (!removed_) {
                    if (!isClosed()) notifyAfterParentChanged();
                    removed_ = true;
                }
                super.removeNotify();
            }
        };

        canvas_.addGLEventListener(new GLEventListener() {
            @Override
            public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
                int viewWidth = width;
                int viewHeight = height;
                if (OS.isMacintosh()) {
                    viewWidth = (int) (width / getDeviceScaleFactor());
                    viewHeight = (int) (height / getDeviceScaleFactor());
                }
                updateViewGeometry(x, y, viewWidth, viewHeight, canvas_.getLocationOnScreen());
                wasResized(viewWidth, viewHeight);
            }

            @Override
            public void init(GLAutoDrawable drawable) {
                renderer_.initialize(drawable.getGL().getGL2());
            }

            @Override
            public void dispose(GLAutoDrawable drawable) {
                renderer_.cleanup(drawable.getGL().getGL2());
            }

            @Override
            public void display(GLAutoDrawable drawable) {
                renderer_.render(drawable.getGL().getGL2());
            }
        });

        MouseAdapter mouseListener = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                sendMouseEvent(toCefMouseEvent(event));
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                sendMouseEvent(toCefMouseEvent(event));
            }

            @Override
            public void mouseEntered(MouseEvent event) {
                sendMouseEvent(toCefMouseEvent(event));
            }

            @Override
            public void mouseExited(MouseEvent event) {
                sendMouseEvent(toCefMouseEvent(event));
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                sendMouseEvent(toCefMouseEvent(event));
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                sendMouseEvent(toCefMouseEvent(event));
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                sendMouseEvent(toCefMouseEvent(event));
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent event) {
                org.cef.event.CefMouseWheelEvent cefEvent =
                        new org.cef.event.CefMouseWheelEvent(event.getScrollType(), event.getX(),
                                event.getY(), event.getWheelRotation(), getCefModifiers(event));
                cefEvent.amount = event.getScrollAmount();
                sendMouseWheelEvent(cefEvent);
            }
        };
        canvas_.addMouseListener(mouseListener);
        canvas_.addMouseMotionListener(mouseListener);
        canvas_.addMouseWheelListener(mouseListener);
        canvas_.addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent event) {
                sendKeyEvent(toCefKeyEvent(event));
            }

            @Override
            public void keyPressed(KeyEvent event) {
                sendKeyEvent(toCefKeyEvent(event));
            }

            @Override
            public void keyReleased(KeyEvent event) {
                sendKeyEvent(toCefKeyEvent(event));
            }
        });
        canvas_.setFocusable(true);
        canvas_.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent event) {
                setFocus(false);
            }

            @Override
            public void focusGained(FocusEvent event) {
                MenuSelectionManager.defaultManager().clearSelectedPath();
                setFocus(true);
            }
        });
        new DropTarget(canvas_, new CefDropTargetListener(this));
    }

    private static org.cef.event.CefMouseEvent toCefMouseEvent(MouseEvent event) {
        int eventId = event.getID();
        if (eventId == MouseEvent.MOUSE_PRESSED) eventId = org.cef.event.CefKeyEvent.KEY_PRESS;
        if (eventId == MouseEvent.MOUSE_RELEASED) eventId = org.cef.event.CefKeyEvent.KEY_RELEASE;
        int button = 0;
        if (event.getButton() == MouseEvent.BUTTON2) button = 2;
        if (event.getButton() == MouseEvent.BUTTON3) button = 1;
        return new org.cef.event.CefMouseEvent(eventId, event.getX(), event.getY(),
                event.getClickCount(), button, getCefModifiers(event));
    }

    private static org.cef.event.CefKeyEvent toCefKeyEvent(KeyEvent event) {
        int eventId = org.cef.event.CefKeyEvent.KEY_TYPE;
        if (event.getID() == KeyEvent.KEY_PRESSED) eventId = org.cef.event.CefKeyEvent.KEY_PRESS;
        if (event.getID() == KeyEvent.KEY_RELEASED) eventId = org.cef.event.CefKeyEvent.KEY_RELEASE;
        return new org.cef.event.CefKeyEvent(eventId, toGlfwKeyCode(event.getKeyCode()),
                event.getKeyChar(), getCefModifiers(event));
    }

    private static int getCefModifiers(InputEvent event) {
        int awtModifiers = event.getModifiersEx();
        int modifiers = 0;
        if ((awtModifiers & InputEvent.SHIFT_DOWN_MASK) != 0) modifiers |= 0x1;
        if ((awtModifiers & InputEvent.CTRL_DOWN_MASK) != 0) modifiers |= 0x2;
        if ((awtModifiers & InputEvent.ALT_DOWN_MASK) != 0) modifiers |= 0x4;
        if ((awtModifiers & InputEvent.META_DOWN_MASK) != 0) modifiers |= 0x8;
        if ((awtModifiers & InputEvent.BUTTON1_DOWN_MASK) != 0) modifiers |= 0x10;
        if ((awtModifiers & InputEvent.BUTTON2_DOWN_MASK) != 0) modifiers |= 0x20;
        if ((awtModifiers & InputEvent.BUTTON3_DOWN_MASK) != 0) modifiers |= 0x40;
        return modifiers;
    }

    private static int toGlfwKeyCode(int awtKeyCode) {
        switch (awtKeyCode) {
            case KeyEvent.VK_ESCAPE:
                return 256;
            case KeyEvent.VK_ENTER:
                return 257;
            case KeyEvent.VK_TAB:
                return 258;
            case KeyEvent.VK_BACK_SPACE:
                return 259;
            case KeyEvent.VK_INSERT:
                return 260;
            case KeyEvent.VK_DELETE:
                return 261;
            case KeyEvent.VK_RIGHT:
                return 262;
            case KeyEvent.VK_LEFT:
                return 263;
            case KeyEvent.VK_DOWN:
                return 264;
            case KeyEvent.VK_UP:
                return 265;
            case KeyEvent.VK_PAGE_UP:
                return 266;
            case KeyEvent.VK_PAGE_DOWN:
                return 267;
            case KeyEvent.VK_HOME:
                return 268;
            case KeyEvent.VK_END:
                return 269;
            default:
                return awtKeyCode;
        }
    }

    @Override
    public void onPopupShow(CefBrowser browser, boolean show) {
        if (!show) {
            renderer_.clearPopupRects();
            invalidate();
        }
    }

    @Override
    public void onPopupSize(CefBrowser browser, Rectangle size) {
        renderer_.onPopupSize(size);
    }

    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects,
            ByteBuffer buffer, int width, int height) {
        GLContext context = canvas_ == null ? null : canvas_.getContext();
        if (context == null || context.makeCurrent() == GLContext.CONTEXT_NOT_CURRENT) return;
        try {
            renderer_.onPaint(canvas_.getGL().getGL2(), popup, dirtyRects, buffer, width, height);
        } finally {
            context.release();
        }

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (canvas_ != null) canvas_.display();
            }
        });
        notifyPaintListeners(browser, popup, dirtyRects, buffer, width, height);
    }

    @Override
    public boolean onCursorChange(CefBrowser browser, final int cursorType) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (canvas_ != null) canvas_.setCursor(new Cursor(cursorType));
            }
        });
        return true;
    }

    private static final class SyntheticDragGestureRecognizer extends DragGestureRecognizer {
        SyntheticDragGestureRecognizer(Component component, int action, MouseEvent triggerEvent) {
            super(new DragSource(), component, action);
            appendEvent(triggerEvent);
        }

        @Override
        protected void registerListeners() {}

        @Override
        protected void unregisterListeners() {}
    }

    private static int getDndAction(int mask) {
        if ((mask & CefDragData.DragOperations.DRAG_OPERATION_COPY) != 0)
            return DnDConstants.ACTION_COPY;
        if ((mask & CefDragData.DragOperations.DRAG_OPERATION_MOVE) != 0)
            return DnDConstants.ACTION_MOVE;
        if ((mask & CefDragData.DragOperations.DRAG_OPERATION_LINK) != 0)
            return DnDConstants.ACTION_LINK;
        return DnDConstants.ACTION_NONE;
    }

    @Override
    public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y) {
        boolean completed = false;
        try {
            int action = getDndAction(mask);
            MouseEvent triggerEvent =
                    new MouseEvent(canvas_, MouseEvent.MOUSE_DRAGGED, 0, 0, x, y, 0, false);
            DragGestureRecognizer recognizer =
                    new SyntheticDragGestureRecognizer(canvas_, action, triggerEvent);
            DragGestureEvent event = new DragGestureEvent(
                    recognizer, action, new Point(x, y), List.of(triggerEvent));
            DragSource.getDefaultDragSource().startDrag(event, null,
                    new StringSelection(dragData.getFragmentText()), new DragSourceAdapter() {
                        @Override
                        public void dragDropEnd(DragSourceDropEvent event) {
                            dragSourceEndedAt(event.getLocation(), action);
                            dragSourceSystemDragEnded();
                        }
                    });
            completed = true;
            return true;
        } finally {
            if (completed) dragData.dispose();
        }
    }

    @Override
    public CompletableFuture<BufferedImage> createScreenshot(boolean nativeResolution) {
        int width = (int) Math.ceil(canvas_.getWidth() * getDeviceScaleFactor());
        int height = (int) Math.ceil(canvas_.getHeight() * getDeviceScaleFactor());
        GL2 gl = canvas_.getGL().getGL2();
        int textureId = renderer_.getTextureID();
        boolean useReadPixels = textureId == 0;
        Callable<BufferedImage> pixelGrabber = new Callable<BufferedImage>() {
            @Override
            public BufferedImage call() {
                BufferedImage screenshot =
                        new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                ByteBuffer buffer = GLBuffers.newDirectByteBuffer(width * height * 4);
                gl.getContext().makeCurrent();
                try {
                    if (useReadPixels) {
                        gl.glReadPixels(
                                0, 0, width, height, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, buffer);
                    } else {
                        gl.glEnable(GL.GL_TEXTURE_2D);
                        gl.glBindTexture(GL.GL_TEXTURE_2D, textureId);
                        gl.glGetTexImage(
                                GL.GL_TEXTURE_2D, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, buffer);
                        gl.glDisable(GL.GL_TEXTURE_2D);
                    }
                } finally {
                    gl.getContext().release();
                }

                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int red = buffer.get() & 0xff;
                        int green = buffer.get() & 0xff;
                        int blue = buffer.get() & 0xff;
                        int alpha = buffer.get() & 0xff;
                        int argb = (alpha << 24) | (red << 16) | (green << 8) | blue;
                        screenshot.setRGB(x, useReadPixels ? height - y - 1 : y, argb);
                    }
                }

                if (nativeResolution || getDeviceScaleFactor() == 1.0) return screenshot;
                int scaledWidth = (int) (screenshot.getWidth() / getDeviceScaleFactor());
                int scaledHeight = (int) (screenshot.getHeight() / getDeviceScaleFactor());
                BufferedImage resized =
                        new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_ARGB);
                AffineTransform transform = AffineTransform.getScaleInstance(
                        1.0 / getDeviceScaleFactor(), 1.0 / getDeviceScaleFactor());
                return new AffineTransformOp(transform, AffineTransformOp.TYPE_BILINEAR)
                        .filter(screenshot, resized);
            }
        };

        if (SwingUtilities.isEventDispatchThread()) {
            try {
                return CompletableFuture.completedFuture(pixelGrabber.call());
            } catch (Exception exception) {
                return failedFuture(exception);
            }
        }

        CompletableFuture<BufferedImage> future = new CompletableFuture<BufferedImage>() {
            private void ensureNotEventDispatchThread() {
                if (SwingUtilities.isEventDispatchThread())
                    throw new IllegalStateException(
                            "Waiting for an OSR screenshot on the AWT event dispatch thread can deadlock");
            }

            @Override
            public BufferedImage get() throws InterruptedException, ExecutionException {
                ensureNotEventDispatchThread();
                return super.get();
            }

            @Override
            public BufferedImage get(long timeout, TimeUnit unit)
                    throws InterruptedException, ExecutionException, TimeoutException {
                ensureNotEventDispatchThread();
                return super.get(timeout, unit);
            }
        };
        canvas_.addGLEventListener(new GLEventListener() {
            @Override
            public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {}

            @Override
            public void init(GLAutoDrawable drawable) {}

            @Override
            public void dispose(GLAutoDrawable drawable) {}

            @Override
            public void display(GLAutoDrawable drawable) {
                canvas_.removeGLEventListener(this);
                try {
                    future.complete(pixelGrabber.call());
                } catch (Exception exception) {
                    future.completeExceptionally(exception);
                }
            }
        });
        canvas_.repaint();
        return future;
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable throwable) {
        CompletableFuture<T> future = new CompletableFuture<T>();
        future.completeExceptionally(throwable);
        return future;
    }
}
