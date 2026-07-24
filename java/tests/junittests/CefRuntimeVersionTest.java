// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.cef.CefApp;
import org.cef.CefApp.CefAppState;
import org.cef.CefApp.CefVersion;
import org.junit.jupiter.api.Test;

/** Minimal native contract exercised on every supported CI architecture. */
@NativeCefTest
class CefRuntimeVersionTest {
    @Test
    void reportsTheExpectedInitializedRuntimeVersions() {
        CefApp app = CefApp.getInstance();
        CefVersion version = app.getVersion();

        assertEquals(CefAppState.INITIALIZED, CefApp.getState());
        assertNotNull(version);
        assertEquals("151.2.3", version.getCefVersion());
        assertEquals("151.0.7922.34", version.getChromeVersion());
    }
}
