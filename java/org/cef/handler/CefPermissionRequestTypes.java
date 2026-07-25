// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.handler;

/**
 * Bit values used by {@link CefPermissionHandler#onShowPermissionPrompt}.
 *
 * <p>Permission sets are represented as raw {@code int} masks so that unknown future bits and
 * their unsigned 32-bit representation survive the Java bridge unchanged. CEF 151's deprecated
 * bit 25 local-network-access value is exposed only under its exact deprecated CEF name so it
 * cannot be mistaken for the active bit 26 local-network permission.
 */
public final class CefPermissionRequestTypes {
    public static final int CEF_PERMISSION_TYPE_NONE = 0;
    public static final int CEF_PERMISSION_TYPE_AR_SESSION = 1 << 0;
    public static final int CEF_PERMISSION_TYPE_CAMERA_PAN_TILT_ZOOM = 1 << 1;
    public static final int CEF_PERMISSION_TYPE_CAMERA_STREAM = 1 << 2;
    public static final int CEF_PERMISSION_TYPE_CAPTURED_SURFACE_CONTROL = 1 << 3;
    public static final int CEF_PERMISSION_TYPE_CLIPBOARD = 1 << 4;
    public static final int CEF_PERMISSION_TYPE_TOP_LEVEL_STORAGE_ACCESS = 1 << 5;
    public static final int CEF_PERMISSION_TYPE_DISK_QUOTA = 1 << 6;
    public static final int CEF_PERMISSION_TYPE_LOCAL_FONTS = 1 << 7;
    public static final int CEF_PERMISSION_TYPE_GEOLOCATION = 1 << 8;
    public static final int CEF_PERMISSION_TYPE_HAND_TRACKING = 1 << 9;
    public static final int CEF_PERMISSION_TYPE_IDENTITY_PROVIDER = 1 << 10;
    public static final int CEF_PERMISSION_TYPE_IDLE_DETECTION = 1 << 11;
    public static final int CEF_PERMISSION_TYPE_MIC_STREAM = 1 << 12;
    public static final int CEF_PERMISSION_TYPE_MIDI_SYSEX = 1 << 13;
    public static final int CEF_PERMISSION_TYPE_MULTIPLE_DOWNLOADS = 1 << 14;
    public static final int CEF_PERMISSION_TYPE_NOTIFICATIONS = 1 << 15;
    public static final int CEF_PERMISSION_TYPE_KEYBOARD_LOCK = 1 << 16;
    public static final int CEF_PERMISSION_TYPE_POINTER_LOCK = 1 << 17;
    public static final int CEF_PERMISSION_TYPE_PROTECTED_MEDIA_IDENTIFIER = 1 << 18;
    public static final int CEF_PERMISSION_TYPE_REGISTER_PROTOCOL_HANDLER = 1 << 19;
    public static final int CEF_PERMISSION_TYPE_STORAGE_ACCESS = 1 << 20;
    public static final int CEF_PERMISSION_TYPE_VR_SESSION = 1 << 21;
    public static final int CEF_PERMISSION_TYPE_WEB_APP_INSTALLATION = 1 << 22;
    public static final int CEF_PERMISSION_TYPE_WINDOW_MANAGEMENT = 1 << 23;
    public static final int CEF_PERMISSION_TYPE_FILE_SYSTEM_ACCESS = 1 << 24;
    /** @deprecated CEF 151 retains this ABI value but no longer uses it for active requests. */
    @Deprecated
    public static final int CEF_PERMISSION_TYPE_LOCAL_NETWORK_ACCESS_DEPRECATED = 1 << 25;
    public static final int CEF_PERMISSION_TYPE_LOCAL_NETWORK = 1 << 26;
    public static final int CEF_PERMISSION_TYPE_LOOPBACK_NETWORK = 1 << 27;
    public static final int CEF_PERMISSION_TYPE_SENSORS = 1 << 28;

    /** All permission bits named by CEF 151, including the explicitly deprecated ABI value. */
    @SuppressWarnings("deprecation")
    public static final int CEF_PERMISSION_TYPE_KNOWN_MASK = CEF_PERMISSION_TYPE_AR_SESSION
            | CEF_PERMISSION_TYPE_CAMERA_PAN_TILT_ZOOM | CEF_PERMISSION_TYPE_CAMERA_STREAM
            | CEF_PERMISSION_TYPE_CAPTURED_SURFACE_CONTROL | CEF_PERMISSION_TYPE_CLIPBOARD
            | CEF_PERMISSION_TYPE_TOP_LEVEL_STORAGE_ACCESS | CEF_PERMISSION_TYPE_DISK_QUOTA
            | CEF_PERMISSION_TYPE_LOCAL_FONTS | CEF_PERMISSION_TYPE_GEOLOCATION
            | CEF_PERMISSION_TYPE_HAND_TRACKING | CEF_PERMISSION_TYPE_IDENTITY_PROVIDER
            | CEF_PERMISSION_TYPE_IDLE_DETECTION | CEF_PERMISSION_TYPE_MIC_STREAM
            | CEF_PERMISSION_TYPE_MIDI_SYSEX | CEF_PERMISSION_TYPE_MULTIPLE_DOWNLOADS
            | CEF_PERMISSION_TYPE_NOTIFICATIONS | CEF_PERMISSION_TYPE_KEYBOARD_LOCK
            | CEF_PERMISSION_TYPE_POINTER_LOCK | CEF_PERMISSION_TYPE_PROTECTED_MEDIA_IDENTIFIER
            | CEF_PERMISSION_TYPE_REGISTER_PROTOCOL_HANDLER | CEF_PERMISSION_TYPE_STORAGE_ACCESS
            | CEF_PERMISSION_TYPE_VR_SESSION | CEF_PERMISSION_TYPE_WEB_APP_INSTALLATION
            | CEF_PERMISSION_TYPE_WINDOW_MANAGEMENT | CEF_PERMISSION_TYPE_FILE_SYSTEM_ACCESS
            | CEF_PERMISSION_TYPE_LOCAL_NETWORK_ACCESS_DEPRECATED
            | CEF_PERMISSION_TYPE_LOCAL_NETWORK | CEF_PERMISSION_TYPE_LOOPBACK_NETWORK
            | CEF_PERMISSION_TYPE_SENSORS;

    private CefPermissionRequestTypes() {}

    /** Returns unknown or intentionally unnamed permission bits without changing them. */
    public static int getUnknownBits(int permissions) {
        return permissions & ~CEF_PERMISSION_TYPE_KNOWN_MASK;
    }
}
