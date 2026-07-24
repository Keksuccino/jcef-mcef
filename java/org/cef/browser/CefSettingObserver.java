// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

/** Observes content and website setting changes on the browser-process UI thread. */
@FunctionalInterface
public interface CefSettingObserver {
    /** Called after the setting represented by {@code contentType} changes. */
    void onSettingChanged(String requestingUrl, String topLevelUrl, CefContentSettingType contentType);
}
