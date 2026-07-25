// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefPaintEvent;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.util.concurrent.atomic.AtomicInteger;

class CefPaintEventTest {
    @Test
    void dirtyRectanglesAreDetachedAndDefensive() {
        Rectangle originalRectangle = new Rectangle(3, 5, 7, 11);
        Rectangle[] originalRectangles = {originalRectangle, null};
        CefPaintEvent event = new CefPaintEvent(null, false, originalRectangles, null, 13, 17);

        originalRectangle.x = 101;
        originalRectangles[0] = new Rectangle(19, 23, 29, 31);
        Rectangle[] firstView = event.getDirtyRects();
        assertEquals(new Rectangle(3, 5, 7, 11), firstView[0]);
        assertNull(firstView[1]);
        assertNotSame(originalRectangles, firstView);
        assertNotSame(originalRectangle, firstView[0]);

        firstView[0].width = 103;
        firstView[1] = new Rectangle(37, 41, 43, 47);
        Rectangle[] secondView = event.getDirtyRects();
        assertEquals(new Rectangle(3, 5, 7, 11), secondView[0]);
        assertNull(secondView[1]);
        assertNotSame(firstView, secondView);
        assertNotSame(firstView[0], secondView[0]);
    }

    @Test
    void renderedFrameViewsAreReadOnlyAndHaveIndependentState() {
        ByteBuffer original = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN);
        original.put(new byte[] {2, 3, 5, 7, 11, 13, 17, 19});
        original.position(1);
        original.limit(7);
        CefPaintEvent event = new CefPaintEvent(null, false, new Rectangle[0], original, 2, 1);

        ByteBuffer firstView = event.getRenderedFrame();
        assertTrue(firstView.isReadOnly());
        assertTrue(firstView.isDirect());
        assertEquals(1, firstView.position());
        assertEquals(7, firstView.limit());
        assertEquals(ByteOrder.LITTLE_ENDIAN, firstView.order());
        firstView.position(3);
        firstView.limit(5);
        firstView.order(ByteOrder.BIG_ENDIAN);

        ByteBuffer secondView = event.getRenderedFrame();
        assertTrue(secondView.isReadOnly());
        assertEquals(1, secondView.position());
        assertEquals(7, secondView.limit());
        assertEquals(ByteOrder.LITTLE_ENDIAN, secondView.order());
        assertThrows(ReadOnlyBufferException.class, () -> secondView.put(1, (byte) 127));
    }

    @Test
    void copiedDirectFrameIsDetachedReadOnlyStorage() {
        assertCopiedFrameIsDetached(true);
    }

    @Test
    void copiedHeapFrameIsDetachedReadOnlyStorage() {
        assertCopiedFrameIsDetached(false);
    }

    private static void assertCopiedFrameIsDetached(boolean direct) {
        ByteBuffer original = (direct ? ByteBuffer.allocateDirect(6) : ByteBuffer.allocate(6)).order(ByteOrder.LITTLE_ENDIAN);
        original.put(new byte[] {23, 29, 31, 37, 41, 43});
        original.position(1);
        original.limit(5);
        CefPaintEvent event = new CefPaintEvent(null, true, new Rectangle[0], original, 1, 1);

        ByteBuffer copy = event.copyRenderedFrame();
        assertTrue(copy.isReadOnly());
        assertEquals(direct, copy.isDirect());
        assertEquals(ByteOrder.LITTLE_ENDIAN, copy.order());
        assertEquals(0, copy.position());
        assertEquals(4, copy.limit());
        original.put(1, (byte) 127);
        byte[] copiedBytes = new byte[copy.remaining()];
        copy.get(copiedBytes);
        assertArrayEquals(new byte[] {29, 31, 37, 41}, copiedBytes);
    }

    @Test
    void nullableCallbackDataRemainsNullable() {
        CefPaintEvent event = new CefPaintEvent(null, false, null, null, 0, 0);
        assertNull(event.getBrowser());
        assertFalse(event.getPopup());
        assertNull(event.getDirtyRects());
        assertNull(event.getRenderedFrame());
        assertNull(event.copyRenderedFrame());
    }

    @Test
    void listenerFailuresDoNotPreventIsolatedDeliveryToLaterListeners() {
        CefBrowserOsr browser = new CefBrowserOsr(null, "about:blank", false, null);
        Rectangle[] dirtyRects = {new Rectangle(2, 3, 5, 7)};
        ByteBuffer renderedFrame = ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN);
        IllegalStateException firstFailure = new IllegalStateException("first listener failed");
        AssertionError laterFailure = new AssertionError("later listener failed");
        AtomicInteger deliveryCount = new AtomicInteger();

        browser.addOnPaintListener(event -> {
            deliveryCount.incrementAndGet();
            event.getDirtyRects()[0].x = 101;
            event.getRenderedFrame().position(4);
            throw firstFailure;
        });
        browser.addOnPaintListener(event -> {
            deliveryCount.incrementAndGet();
            assertEquals(new Rectangle(2, 3, 5, 7), event.getDirtyRects()[0]);
            assertEquals(0, event.getRenderedFrame().position());
            assertEquals(ByteOrder.LITTLE_ENDIAN, event.getRenderedFrame().order());
        });
        browser.addOnPaintListener(event -> {
            deliveryCount.incrementAndGet();
            throw laterFailure;
        });
        browser.addOnPaintListener(event -> deliveryCount.incrementAndGet());
        browser.addOnPaintListener(event -> {
            deliveryCount.incrementAndGet();
            throw firstFailure;
        });
        browser.addOnPaintListener(event -> deliveryCount.incrementAndGet());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> browser.onPaint(null, false, dirtyRects, renderedFrame, 2, 2));
        assertSame(firstFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(laterFailure, thrown.getSuppressed()[0]);
        assertEquals(6, deliveryCount.get());
    }
}
