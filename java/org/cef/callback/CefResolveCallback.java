// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.callback;

/** Callback executed on the browser-process UI thread after host resolution completes. */
@FunctionalInterface
public interface CefResolveCallback {
    /** Reports the complete resolution result, including the exact CEF error code. */
    void onResolveCompleted(CefResolveResult result);
}
