// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import org.cef.callback.CefCommandLine;

/** Configures deterministic virtual capture hardware without bypassing permission handling. */
final class PermissionTestCommandLine {
    static final String FAKE_MEDIA_DEVICE_SWITCH = "use-fake-device-for-media-stream";
    static final String BYPASS_MEDIA_PERMISSION_UI_SWITCH = "use-fake-ui-for-media-stream";
    static final String GRANT_ALL_MEDIA_PERMISSIONS_SWITCH = "enable-media-stream";

    private PermissionTestCommandLine() {}

    static void configureBrowserProcess(String processType, CefCommandLine commandLine) {
        if (!processType.isEmpty()) return;

        // These switches would grant or auto-approve the request before the bridge under test can
        // make a decision. Remove inherited/user-provided values so this fixture proves the real
        // permission callback path while still using deterministic virtual capture hardware.
        commandLine.removeSwitch(BYPASS_MEDIA_PERMISSION_UI_SWITCH);
        commandLine.removeSwitch(GRANT_ALL_MEDIA_PERMISSIONS_SWITCH);
        if (!commandLine.hasSwitch(FAKE_MEDIA_DEVICE_SWITCH))
            commandLine.appendSwitch(FAKE_MEDIA_DEVICE_SWITCH);
    }
}
