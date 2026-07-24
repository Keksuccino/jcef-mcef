// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.handler;

import org.cef.browser.CefBrowser;
import org.cef.misc.CefAudioParameters;
import org.cef.misc.DataPointer;

/**
 * Convenience adapter for {@link CefAudioHandler}. See the interface methods for their individual
 * CEF callback-thread and native-data lifetime contracts.
 */
public abstract class CefAudioHandlerAdapter implements CefAudioHandler {
    @Override
    public boolean getAudioParameters(CefBrowser browser, CefAudioParameters params) {
        return false;
    }

    @Override
    public void onAudioStreamStarted(CefBrowser browser, CefAudioParameters params, int channels) {}

    @Override
    public void onAudioStreamPacket(CefBrowser browser, DataPointer data, int frames, long pts) {}

    @Override
    public void onAudioStreamStopped(CefBrowser browser) {}

    @Override
    public void onAudioStreamError(CefBrowser browser, String text) {}
}
