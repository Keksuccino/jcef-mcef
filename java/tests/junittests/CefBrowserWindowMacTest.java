// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.cef.OS;
import org.cef.browser.mac.CefBrowserWindowMac;
import org.junit.jupiter.api.Test;

import java.awt.Canvas;

class CefBrowserWindowMacTest {
    @Test
    void nullComponentHasNoHandle() {
        assertEquals(0, new CefBrowserWindowMac().getWindowHandle(null));
    }

    @Test
    void undisplayableHeavyweightComponentHasNoHandle() {
        assumeTrue(OS.isMacintosh());
        assertEquals(0, new CefBrowserWindowMac().getWindowHandle(new Canvas()));
    }
}
