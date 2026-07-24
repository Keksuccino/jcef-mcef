// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.handler;

import org.cef.browser.CefBrowser;

import java.awt.Rectangle;

/** Convenience adapter with a no-op find-result implementation. */
public abstract class CefFindHandlerAdapter implements CefFindHandler {
    @Override
    public void onFindResult(CefBrowser browser, int identifier, int count, Rectangle selectionRect, int activeMatchOrdinal, boolean finalUpdate) {}
}
