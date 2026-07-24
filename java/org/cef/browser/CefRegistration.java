// Copyright (c) 2024 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

/**
 * Owns a native observer registration. The observer remains registered until this handle is
 * closed or disposed.
 */
public abstract class CefRegistration implements AutoCloseable {
    /**
     * Unregisters the observer and releases the native reference. Implementations must make this
     * operation idempotent.
     */
    public abstract void dispose();

    @Override
    public final void close() {
        dispose();
    }

    @Override
    protected void finalize() throws Throwable {
        dispose();
        super.finalize();
    }
}
