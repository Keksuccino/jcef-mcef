// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefBrowser_N;

/** Child-process fixture that closes an uncreated browser without loading native JCEF. */
public final class CefBrowserUncreatedCloseProcess {
    private CefBrowserUncreatedCloseProcess() {}

    public static void main(String[] args) {
        CefBrowserOsr browser = new CefBrowserOsr(null, "about:blank", false, null);
        browser.close(true);
        assertNoDeclaredFinalizer();
    }

    private static void assertNoDeclaredFinalizer() {
        try {
            CefBrowser_N.class.getDeclaredMethod("finalize");
        } catch (NoSuchMethodException expected) {
            return;
        }
        throw new AssertionError("CefBrowser_N must not declare finalize()");
    }
}
