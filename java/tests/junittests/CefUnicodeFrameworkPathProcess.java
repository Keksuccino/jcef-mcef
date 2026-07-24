// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import org.cef.CefApp;

/** Separate-process fixture because dynamically loaded CEF supports one framework per process. */
public final class CefUnicodeFrameworkPathProcess {
    private CefUnicodeFrameworkPathProcess() {}

    public static void main(String[] args) {
        if (!CefApp.startup(args))
            throw new IllegalStateException("CEF startup through Unicode framework path failed");
    }
}
