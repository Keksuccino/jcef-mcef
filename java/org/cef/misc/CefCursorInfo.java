// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.misc;

import java.nio.ByteBuffer;

/**
 * Immutable, Java-owned snapshot of a CEF custom cursor.
 *
 * <p>The pixel buffer contains {@code width * height * 4} bytes in BGRA order with an upper-left
 * origin. It is copied before CEF's callback returns, so this object can safely be retained and
 * used on another thread. CEF's platform-specific cursor handle is intentionally not exposed: its
 * representation varies by operating system and CEF only keeps it alive for the native callback.
 */
public final class CefCursorInfo {
    private static final int BYTES_PER_PIXEL = 4;

    private final int hotspotX_;
    private final int hotspotY_;
    private final float imageScaleFactor_;
    private final int width_;
    private final int height_;
    private final ByteBuffer buffer_;

    /**
     * Creates an owned custom-cursor snapshot by defensively copying {@code bgraPixels}.
     *
     * @param hotspotX Zero-based horizontal hotspot coordinate in image pixels, or zero for an
     *        empty image.
     * @param hotspotY Zero-based vertical hotspot coordinate in image pixels, or zero for an empty
     *        image.
     * @param imageScaleFactor Positive finite scale factor of the cursor image.
     * @param width Non-negative image width in pixels. It may be zero only when {@code height} is
     *        also zero.
     * @param height Non-negative image height in pixels. It may be zero only when {@code width} is
     *        also zero.
     * @param bgraPixels Exactly {@code width * height * 4} bytes in BGRA order. It may be
     *        {@code null} only for an empty image.
     * @throws IllegalArgumentException if a value or the pixel-buffer size is invalid.
     */
    public CefCursorInfo(int hotspotX, int hotspotY, float imageScaleFactor, int width, int height, byte[] bgraPixels) {
        this(hotspotX, hotspotY, imageScaleFactor, width, height, copyPixels(hotspotX, hotspotY, imageScaleFactor, width, height, bgraPixels));
    }

    private CefCursorInfo(int hotspotX, int hotspotY, float imageScaleFactor, int width, int height, ByteBuffer buffer) {
        validate(hotspotX, hotspotY, imageScaleFactor, width, height, buffer);
        hotspotX_ = hotspotX;
        hotspotY_ = hotspotY;
        imageScaleFactor_ = imageScaleFactor;
        width_ = width;
        height_ = height;
        buffer_ = buffer.slice().asReadOnlyBuffer();
    }

    /**
     * Takes ownership of a fresh JNI heap buffer. Native code is the only caller, and no other Java
     * reference to its backing array is published, which copies CEF's pixels exactly once.
     */
    @SuppressWarnings("unused")
    private static CefCursorInfo createNative(int hotspotX, int hotspotY, float imageScaleFactor, int width, int height, ByteBuffer buffer) {
        return new CefCursorInfo(hotspotX, hotspotY, imageScaleFactor, width, height, buffer);
    }

    public int getHotspotX() {
        return hotspotX_;
    }

    public int getHotspotY() {
        return hotspotY_;
    }

    public float getImageScaleFactor() {
        return imageScaleFactor_;
    }

    public int getWidth() {
        return width_;
    }

    public int getHeight() {
        return height_;
    }

    /**
     * Returns a fresh read-only view of the complete BGRA buffer at position zero. The view does
     * not expose its backing array, and changing its position does not affect later views.
     */
    public ByteBuffer getBuffer() {
        return buffer_.asReadOnlyBuffer();
    }

    private static ByteBuffer copyPixels(int hotspotX, int hotspotY, float imageScaleFactor, int width, int height, byte[] bgraPixels) {
        ByteBuffer source = bgraPixels == null ? null : ByteBuffer.wrap(bgraPixels);
        if (source == null && width == 0 && height == 0) source = ByteBuffer.allocate(0);
        // Validate before cloning so malformed dimensions cannot force a needless large allocation.
        validate(hotspotX, hotspotY, imageScaleFactor, width, height, source);
        byte[] copy = new byte[source.remaining()];
        source.get(copy);
        return ByteBuffer.wrap(copy);
    }

    private static void validate(int hotspotX, int hotspotY, float imageScaleFactor, int width, int height, ByteBuffer buffer) {
        if (width < 0 || height < 0 || (width == 0) != (height == 0))
            throw new IllegalArgumentException("width and height must both be positive or both be zero");
        boolean empty = width == 0;
        if (empty && (hotspotX != 0 || hotspotY != 0))
            throw new IllegalArgumentException("an empty cursor must have hotspot (0, 0)");
        if (!empty && (hotspotX < 0 || hotspotX >= width || hotspotY < 0 || hotspotY >= height))
            throw new IllegalArgumentException("hotspot must be inside the cursor image");
        if (!Float.isFinite(imageScaleFactor) || imageScaleFactor <= 0.0f)
            throw new IllegalArgumentException("imageScaleFactor must be finite and positive");
        if (buffer == null) throw new IllegalArgumentException("bgraPixels must not be null");

        long pixelCount = (long) width * (long) height;
        if (pixelCount > Integer.MAX_VALUE / BYTES_PER_PIXEL)
            throw new IllegalArgumentException("cursor image is too large for a Java byte array");
        int expectedLength = (int) (pixelCount * BYTES_PER_PIXEL);
        if (buffer.remaining() != expectedLength)
            throw new IllegalArgumentException("bgraPixels length must equal width * height * 4");
    }
}
