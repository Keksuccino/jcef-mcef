// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.handler;

import org.cef.browser.CefBrowser;
import org.cef.misc.CefAudioParameters;
import org.cef.misc.DataPointer;

/**
 * Implement this interface to handle events related to audio playing.
 *
 * <p>Audio callbacks use multiple CEF threads as documented on each method. Implementations must
 * provide any synchronization or dispatching required by their application.
 */
public interface CefAudioHandler {
    /**
     * Called on the CEF UI thread to configure audio capture. {@code params} contains CEF's
     * defaults and may be mutated. Valid mutations are copied back to CEF only when this method
     * returns {@code true}; returning {@code false} cancels audio capture.
     */
    boolean getAudioParameters(CefBrowser browser, CefAudioParameters params);

    /**
     * Called on a browser audio-capture thread when streaming starts. The supplied parameters are
     * a snapshot of the active native configuration and mutations do not reconfigure the stream.
     */
    void onAudioStreamStarted(CefBrowser browser, CefAudioParameters params, int channels);

    /**
     * Called on the audio-stream thread for a planar floating-point PCM packet.
     *
     * <p>{@code data} is valid only on the current thread and for the duration of this callback. It
     * is invalidated immediately when the callback returns, including when it throws, so copy any
     * samples that must be retained. Read channel {@code c} and frame {@code f} with {@code
     * data.getData(c).getFloat(f)}, where {@code 0 <= c < channels} from the preceding start
     * callback and {@code 0 <= f < frames}. Out-of-range access fails deterministically. {@code
     * pts} is the presentation timestamp in milliseconds since the Unix epoch.
     */
    void onAudioStreamPacket(CefBrowser browser, DataPointer data, int frames, long pts);

    /** Called on the CEF UI thread after an audio stream stops. */
    void onAudioStreamStopped(CefBrowser browser);

    /**
     * Called on the CEF UI thread for errors during stream creation or on the audio-stream thread
     * for errors during capture. The stream is stopped immediately.
     */
    void onAudioStreamError(CefBrowser browser, String text);
}
