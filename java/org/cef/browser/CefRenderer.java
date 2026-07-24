// Copyright (c) 2013 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

import com.jogamp.opengl.GL2;

import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/** OpenGL renderer used by the upstream-compatible Swing OSR browser. */
class CefRenderer {
    private final boolean transparent_;
    private GL2 initializedContext_ = null;
    private final int[] textureId_ = new int[1];
    private int viewWidth_ = 0;
    private int viewHeight_ = 0;
    private float spinX_ = 0f;
    private float spinY_ = 0f;
    private final Rectangle popupRect_ = new Rectangle(0, 0, 0, 0);
    private final Rectangle originalPopupRect_ = new Rectangle(0, 0, 0, 0);
    private boolean useDrawPixels_ = false;

    CefRenderer(boolean transparent) {
        transparent_ = transparent;
    }

    boolean isTransparent() {
        return transparent_;
    }

    int getTextureID() {
        return textureId_[0];
    }

    @SuppressWarnings("static-access")
    void initialize(GL2 gl) {
        if (initializedContext_ == gl) return;

        initializedContext_ = gl;
        if (!gl.getContext().isHardwareRasterizer()) {
            // Windows Remote Desktop requires the glDrawPixels fallback.
            System.out.println(
                    "opengl rendering may be slow as hardware rendering isn't available");
            useDrawPixels_ = true;
            return;
        }

        gl.glHint(gl.GL_POLYGON_SMOOTH_HINT, gl.GL_NICEST);
        gl.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        gl.glPixelStorei(gl.GL_UNPACK_ALIGNMENT, 1);
        gl.glGenTextures(1, textureId_, 0);
        assert textureId_[0] != 0;
        gl.glBindTexture(gl.GL_TEXTURE_2D, textureId_[0]);
        gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MIN_FILTER, gl.GL_NEAREST);
        gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MAG_FILTER, gl.GL_NEAREST);
        gl.glTexEnvf(gl.GL_TEXTURE_ENV, gl.GL_TEXTURE_ENV_MODE, gl.GL_MODULATE);
    }

    void cleanup(GL2 gl) {
        if (textureId_[0] != 0) gl.glDeleteTextures(1, textureId_, 0);
        textureId_[0] = 0;
        initializedContext_ = null;
        viewWidth_ = 0;
        viewHeight_ = 0;
    }

    @SuppressWarnings("static-access")
    void render(GL2 gl) {
        if (useDrawPixels_ || viewWidth_ == 0 || viewHeight_ == 0) return;
        assert initializedContext_ != null;

        final float[] vertexData = {0.0f, 1.0f, -1.0f, -1.0f, 0.0f, 1.0f, 1.0f, 1.0f, -1.0f, 0.0f,
                1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f, -1.0f, 1.0f, 0.0f};
        FloatBuffer vertices = FloatBuffer.wrap(vertexData);

        gl.glClear(gl.GL_COLOR_BUFFER_BIT | gl.GL_DEPTH_BUFFER_BIT);
        gl.glMatrixMode(gl.GL_MODELVIEW);
        gl.glLoadIdentity();
        gl.glViewport(0, 0, viewWidth_, viewHeight_);
        gl.glMatrixMode(gl.GL_PROJECTION);
        gl.glLoadIdentity();

        gl.glPushAttrib(gl.GL_ALL_ATTRIB_BITS);
        gl.glBegin(gl.GL_QUADS);
        gl.glColor4f(1.0f, 0.0f, 0.0f, 1.0f);
        gl.glVertex2f(-1.0f, -1.0f);
        gl.glVertex2f(1.0f, -1.0f);
        gl.glColor4f(0.0f, 0.0f, 1.0f, 1.0f);
        gl.glVertex2f(1.0f, 1.0f);
        gl.glVertex2f(-1.0f, 1.0f);
        gl.glEnd();
        gl.glPopAttrib();

        if (spinX_ != 0) gl.glRotatef(-spinX_, 1.0f, 0.0f, 0.0f);
        if (spinY_ != 0) gl.glRotatef(-spinY_, 0.0f, 1.0f, 0.0f);
        if (transparent_) {
            gl.glBlendFunc(gl.GL_ONE, gl.GL_ONE_MINUS_SRC_ALPHA);
            gl.glEnable(gl.GL_BLEND);
        }

        gl.glEnable(gl.GL_TEXTURE_2D);
        assert textureId_[0] != 0;
        gl.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        gl.glBindTexture(gl.GL_TEXTURE_2D, textureId_[0]);
        gl.glInterleavedArrays(gl.GL_T2F_V3F, 0, vertices);
        gl.glDrawArrays(gl.GL_QUADS, 0, 4);
        gl.glDisable(gl.GL_TEXTURE_2D);
        if (transparent_) gl.glDisable(gl.GL_BLEND);
    }

    void onPopupSize(Rectangle rectangle) {
        if (rectangle.width <= 0 || rectangle.height <= 0) return;
        originalPopupRect_.setBounds(rectangle);
        popupRect_.setBounds(getPopupRectInWebView(originalPopupRect_));
    }

    Rectangle getPopupRect() {
        return new Rectangle(popupRect_);
    }

    private Rectangle getPopupRectInWebView(Rectangle originalRectangle) {
        Rectangle rectangle = new Rectangle(originalRectangle);
        if (rectangle.x < 0) rectangle.x = 0;
        if (rectangle.y < 0) rectangle.y = 0;
        if (rectangle.x + rectangle.width > viewWidth_) rectangle.x = viewWidth_ - rectangle.width;
        if (rectangle.y + rectangle.height > viewHeight_)
            rectangle.y = viewHeight_ - rectangle.height;
        if (rectangle.x < 0) rectangle.x = 0;
        if (rectangle.y < 0) rectangle.y = 0;
        return rectangle;
    }

    void clearPopupRects() {
        popupRect_.setBounds(0, 0, 0, 0);
        originalPopupRect_.setBounds(0, 0, 0, 0);
    }

    @SuppressWarnings("static-access")
    void onPaint(GL2 gl, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width,
            int height) {
        initialize(gl);

        if (useDrawPixels_) {
            gl.glRasterPos2f(-1, 1);
            gl.glPixelZoom(1, -1);
            gl.glDrawPixels(width, height, GL2.GL_BGRA, GL2.GL_UNSIGNED_BYTE, buffer);
            return;
        }

        if (transparent_) gl.glEnable(gl.GL_BLEND);
        gl.glEnable(gl.GL_TEXTURE_2D);
        assert textureId_[0] != 0;
        gl.glBindTexture(gl.GL_TEXTURE_2D, textureId_[0]);

        if (!popup) {
            int oldWidth = viewWidth_;
            int oldHeight = viewHeight_;
            viewWidth_ = width;
            viewHeight_ = height;
            gl.glPixelStorei(gl.GL_UNPACK_ROW_LENGTH, viewWidth_);
            if (oldWidth != viewWidth_ || oldHeight != viewHeight_) {
                gl.glPixelStorei(gl.GL_UNPACK_SKIP_PIXELS, 0);
                gl.glPixelStorei(gl.GL_UNPACK_SKIP_ROWS, 0);
                gl.glTexImage2D(gl.GL_TEXTURE_2D, 0, gl.GL_RGBA, viewWidth_, viewHeight_, 0,
                        gl.GL_BGRA, gl.GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
            } else {
                for (Rectangle rectangle : dirtyRects) {
                    gl.glPixelStorei(gl.GL_UNPACK_SKIP_PIXELS, rectangle.x);
                    gl.glPixelStorei(gl.GL_UNPACK_SKIP_ROWS, rectangle.y);
                    gl.glTexSubImage2D(gl.GL_TEXTURE_2D, 0, rectangle.x, rectangle.y,
                            rectangle.width, rectangle.height, gl.GL_BGRA,
                            gl.GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
                }
            }
        } else if (popupRect_.width > 0 && popupRect_.height > 0) {
            int skipPixels = 0;
            int x = popupRect_.x;
            int skipRows = 0;
            int y = popupRect_.y;
            int popupWidth = width;
            int popupHeight = height;
            if (x < 0) {
                skipPixels = -x;
                x = 0;
            }
            if (y < 0) {
                skipRows = -y;
                y = 0;
            }
            if (x + popupWidth > viewWidth_) popupWidth -= x + popupWidth - viewWidth_;
            if (y + popupHeight > viewHeight_) popupHeight -= y + popupHeight - viewHeight_;
            gl.glPixelStorei(gl.GL_UNPACK_ROW_LENGTH, width);
            gl.glPixelStorei(gl.GL_UNPACK_SKIP_PIXELS, skipPixels);
            gl.glPixelStorei(gl.GL_UNPACK_SKIP_ROWS, skipRows);
            gl.glTexSubImage2D(gl.GL_TEXTURE_2D, 0, x, y, popupWidth, popupHeight, gl.GL_BGRA,
                    gl.GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        }

        gl.glDisable(gl.GL_TEXTURE_2D);
        if (transparent_) gl.glDisable(gl.GL_BLEND);
    }

    void setSpin(float spinX, float spinY) {
        spinX_ = spinX;
        spinY_ = spinY;
    }

    void incrementSpin(float spinDeltaX, float spinDeltaY) {
        spinX_ -= spinDeltaX;
        spinY_ -= spinDeltaY;
    }
}
