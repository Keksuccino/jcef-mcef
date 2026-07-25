// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.handler;

/** Result values used to complete and dismiss CEF permission prompts. */
public final class CefPermissionRequestResult {
    public static final int CEF_PERMISSION_RESULT_ACCEPT = 0;
    public static final int CEF_PERMISSION_RESULT_DENY = 1;
    public static final int CEF_PERMISSION_RESULT_DISMISS = 2;
    public static final int CEF_PERMISSION_RESULT_IGNORE = 3;
    public static final int CEF_PERMISSION_RESULT_NUM_VALUES = 4;

    private CefPermissionRequestResult() {}

    /** Returns whether {@code result} names a completable CEF 151 permission result. */
    public static boolean isValid(int result) {
        return result >= CEF_PERMISSION_RESULT_ACCEPT && result < CEF_PERMISSION_RESULT_NUM_VALUES;
    }
}
