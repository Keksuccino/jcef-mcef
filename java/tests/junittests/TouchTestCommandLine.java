// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import org.cef.callback.CefCommandLine;

/** Enables Chromium touch handling deterministically for native JUnit processes. */
final class TouchTestCommandLine {
    static final String TOUCH_EVENTS_SWITCH = "touch-events";
    static final String TOUCH_EVENTS_ENABLED = "enabled";

    private TouchTestCommandLine() {}

    static void configureBrowserProcess(String processType, CefCommandLine commandLine) {
        if (!processType.isEmpty()) return;
        if (commandLine.hasSwitch(TOUCH_EVENTS_SWITCH)) {
            if (TOUCH_EVENTS_ENABLED.equals(commandLine.getSwitchValue(TOUCH_EVENTS_SWITCH))) return;
            commandLine.removeSwitch(TOUCH_EVENTS_SWITCH);
        }
        commandLine.appendSwitchWithValue(TOUCH_EVENTS_SWITCH, TOUCH_EVENTS_ENABLED);
    }
}
