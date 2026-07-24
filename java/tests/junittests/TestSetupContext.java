// Copyright (c) 2019 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.Optional;

// Stores global test setup state for access from package classes.
class TestSetupContext {
    private static final String WINDOWLESS_RENDERING_PARAMETER = "jcef.windowless_rendering_enabled";
    private static boolean debugPrint_ = false;
    private static boolean windowlessRenderingEnabled_ = true;

    // Debug print statements may be enabled via `--config debugPrint=true`.
    static boolean debugPrint() {
        return debugPrint_;
    }

    // Initialize from global configuration parameters.
    static void initialize(ExtensionContext context) {
        Optional<String> debugPrint = context.getConfigurationParameter("debugPrint");
        if (debugPrint.isPresent() && debugPrint.get().equalsIgnoreCase("true")) {
            debugPrint_ = true;
        }

        Optional<String> windowlessRendering = context.getConfigurationParameter(WINDOWLESS_RENDERING_PARAMETER);
        if (windowlessRendering.isPresent()) {
            String value = windowlessRendering.get();
            if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))
                throw new IllegalArgumentException("JUnit parameter " + WINDOWLESS_RENDERING_PARAMETER + " must be true or false; found " + value);
            windowlessRenderingEnabled_ = Boolean.parseBoolean(value);
        }
    }

    static boolean windowlessRenderingEnabled() {
        return windowlessRenderingEnabled_;
    }
}
