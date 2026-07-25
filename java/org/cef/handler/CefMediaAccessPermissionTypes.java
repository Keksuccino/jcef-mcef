// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.handler;

/**
 * Bit values used by {@link CefPermissionHandler#onRequestMediaAccessPermission}.
 *
 * <p>Permission sets are represented as raw {@code int} masks so that bits added by a newer CEF
 * runtime survive the Java bridge unchanged.
 */
public final class CefMediaAccessPermissionTypes {
    public static final int CEF_MEDIA_PERMISSION_NONE = 0;
    public static final int CEF_MEDIA_PERMISSION_DEVICE_AUDIO_CAPTURE = 1 << 0;
    public static final int CEF_MEDIA_PERMISSION_DEVICE_VIDEO_CAPTURE = 1 << 1;
    public static final int CEF_MEDIA_PERMISSION_DESKTOP_AUDIO_CAPTURE = 1 << 2;
    public static final int CEF_MEDIA_PERMISSION_DESKTOP_VIDEO_CAPTURE = 1 << 3;

    /** All media permission bits named by CEF 151. */
    public static final int CEF_MEDIA_PERMISSION_KNOWN_MASK =
            CEF_MEDIA_PERMISSION_DEVICE_AUDIO_CAPTURE | CEF_MEDIA_PERMISSION_DEVICE_VIDEO_CAPTURE
            | CEF_MEDIA_PERMISSION_DESKTOP_AUDIO_CAPTURE
            | CEF_MEDIA_PERMISSION_DESKTOP_VIDEO_CAPTURE;

    private CefMediaAccessPermissionTypes() {}

    /** Returns bits that are not named by the CEF 151 Java API. */
    public static int getUnknownBits(int permissions) {
        return permissions & ~CEF_MEDIA_PERMISSION_KNOWN_MASK;
    }
}
