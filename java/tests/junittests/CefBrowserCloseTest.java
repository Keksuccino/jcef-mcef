// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.junit.jupiter.api.Test;

import java.awt.Component;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

@NativeCefTest
class CefBrowserCloseTest {
    @Test
    void headlessBrowserAllowsCefToCompleteClose() {
        CefBrowser browser = new CefBrowserOsr(null, "about:blank", false, null);

        assertFalse(browser.doClose());
    }

    @Test
    void closeAllowedBypassesComponentWindowOwner() {
        CefBrowser browser = new ComponentBackedBrowser();
        browser.setCloseAllowed();

        assertFalse(browser.doClose());
    }

    @Test
    void detachedComponentContinuesCloseWithoutWindowOwner() throws Exception {
        CefBrowser browser = new ComponentBackedBrowser();

        assertTrue(browser.doClose());
        SwingUtilities.invokeAndWait(() -> {});
        assertFalse(browser.doClose());
    }

    private static final class ComponentBackedBrowser extends CefBrowserOsr {
        private final JPanel component_ = new JPanel();

        private ComponentBackedBrowser() {
            super(null, "about:blank", false, null);
        }

        @Override
        public Component getUIComponent() {
            return component_;
        }
    }
}
