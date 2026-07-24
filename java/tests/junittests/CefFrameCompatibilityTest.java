// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.browser.CefFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class CefFrameCompatibilityTest {
    @Test
    void legacyImplementationsGetExplicitFallbacksForNewOperations() {
        CefFrame frame = new LegacyCefFrame();

        assertUnsupported("getBrowser", frame::getBrowser);
        assertUnsupported("viewSource", frame::viewSource);
        assertUnsupported("getSource", () -> frame.getSource(value -> {}));
        assertUnsupported("getText", () -> frame.getText(value -> {}));
        assertUnsupported("loadURL", () -> frame.loadURL("about:blank"));
        assertUnsupported("pasteAndMatchStyle", frame::pasteAndMatchStyle);
        assertUnsupported("delete", frame::delete);
    }

    @Test
    void compatibilityFallbacksRejectNullArgumentsBeforeDispatch() {
        CefFrame frame = new LegacyCefFrame();

        assertThrows(NullPointerException.class, () -> frame.getSource(null));
        assertThrows(NullPointerException.class, () -> frame.getText(null));
        assertThrows(NullPointerException.class, () -> frame.loadRequest(null));
        assertThrows(NullPointerException.class, () -> frame.loadURL(null));
    }

    private static void assertUnsupported(String operation, Executable executable) {
        UnsupportedOperationException exception = assertThrows(UnsupportedOperationException.class, executable);
        assertTrue(exception.getMessage().contains(operation));
    }

    // Deliberately implements only the pre-bridge CefFrame API. Compiling this class verifies that
    // adding the portable frame operations does not break existing third-party implementations.
    private static final class LegacyCefFrame implements CefFrame {
        @Override
        public void dispose() {}

        @Override
        public String getIdentifier() {
            return "";
        }

        @Override
        public String getURL() {
            return "";
        }

        @Override
        public String getName() {
            return "";
        }

        @Override
        public boolean isMain() {
            return false;
        }

        @Override
        public boolean isValid() {
            return false;
        }

        @Override
        public boolean isFocused() {
            return false;
        }

        @Override
        public CefFrame getParent() {
            return null;
        }

        @Override
        public void executeJavaScript(String code, String url, int line) {}

        @Override
        public void undo() {}

        @Override
        public void redo() {}

        @Override
        public void cut() {}

        @Override
        public void copy() {}

        @Override
        public void paste() {}

        @Override
        public void selectAll() {}
    }
}
