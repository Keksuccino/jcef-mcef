// Copyright (c) 2013 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

import java.awt.event.KeyEvent;
import java.util.HashSet;
import java.util.Set;

/**
 * Infers auto-repeat for AWT, whose public key-event API does not expose the native repeat bit.
 * State must be cleared whenever focus or browser ownership can end because AWT may then omit the
 * matching release event. The latest press context intentionally survives multiple typed events:
 * one repeated physical press can emit multiple UTF-16 code units.
 */
final class CefAwtKeyRepeatTracker {
    private final Set<Long> pressedKeys_ = new HashSet<>();
    private boolean hasTypedContext_;
    private long typedContextIdentity_;
    private boolean typedContextRepeated_;

    synchronized boolean update(KeyEvent event) {
        if (event.getID() == KeyEvent.KEY_PRESSED) {
            long identity = getKeyIdentity(event);
            boolean repeated = !pressedKeys_.add(identity);
            hasTypedContext_ = true;
            typedContextIdentity_ = identity;
            typedContextRepeated_ = repeated;
            return repeated;
        }
        if (event.getID() == KeyEvent.KEY_TYPED) return hasTypedContext_ && typedContextRepeated_;
        if (event.getID() == KeyEvent.KEY_RELEASED) {
            long identity = getKeyIdentity(event);
            pressedKeys_.remove(identity);
            if (hasTypedContext_ && typedContextIdentity_ == identity) clearTypedContext();
        }
        return false;
    }

    synchronized void clear() {
        pressedKeys_.clear();
        clearTypedContext();
    }

    private void clearTypedContext() {
        hasTypedContext_ = false;
        typedContextIdentity_ = 0;
        typedContextRepeated_ = false;
    }

    private static long getKeyIdentity(KeyEvent event) {
        return ((long) event.getKeyCode() << Integer.SIZE) | (event.getKeyLocation() & 0xFFFFFFFFL);
    }
}
