// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.misc.CefAudioParameters;
import org.cef.misc.CefChannelLayout;
import org.junit.jupiter.api.Test;

class CefAudioParametersTest {
    @Test
    void constructsAndMutatesValidParameters() {
        CefAudioParameters parameters = new CefAudioParameters(CefChannelLayout.CEF_CHANNEL_LAYOUT_STEREO, 44_100, 512);

        assertSame(CefChannelLayout.CEF_CHANNEL_LAYOUT_STEREO, parameters.getChannelLayout());
        assertEquals(44_100, parameters.getSampleRate());
        assertEquals(512, parameters.getFramesPerBuffer());
        assertTrue(parameters.isValid());

        parameters.setChannelLayout(CefChannelLayout.CEF_CHANNEL_LAYOUT_7_1_4);
        parameters.setSampleRate(48_000);
        parameters.setFramesPerBuffer(1_024);

        assertSame(CefChannelLayout.CEF_CHANNEL_LAYOUT_7_1_4, parameters.channelLayout);
        assertEquals(48_000, parameters.sampleRate);
        assertEquals(1_024, parameters.framesPerBuffer);
        assertTrue(parameters.isValid());
        parameters.validate();
    }

    @Test
    void constructorRejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> new CefAudioParameters(null, 44_100, 512));
        assertThrows(IllegalArgumentException.class, () -> new CefAudioParameters(CefChannelLayout.CEF_CHANNEL_LAYOUT_MAX, 44_100, 512));
        assertThrows(IllegalArgumentException.class, () -> new CefAudioParameters(CefChannelLayout.CEF_CHANNEL_LAYOUT_NONE, 44_100, 512));
        assertThrows(IllegalArgumentException.class, () -> new CefAudioParameters(CefChannelLayout.CEF_CHANNEL_LAYOUT_UNSUPPORTED, 44_100, 512));
        assertThrows(IllegalArgumentException.class, () -> new CefAudioParameters(CefChannelLayout.CEF_CHANNEL_LAYOUT_DISCRETE, 44_100, 512));
        assertThrows(IllegalArgumentException.class, () -> new CefAudioParameters(CefChannelLayout.CEF_CHANNEL_LAYOUT_BITSTREAM, 44_100, 512));
        assertThrows(IllegalArgumentException.class, () -> new CefAudioParameters(CefChannelLayout.CEF_CHANNEL_LAYOUT_STEREO, 0, 512));
        assertThrows(IllegalArgumentException.class, () -> new CefAudioParameters(CefChannelLayout.CEF_CHANNEL_LAYOUT_STEREO, -1, 512));
        assertThrows(IllegalArgumentException.class, () -> new CefAudioParameters(CefChannelLayout.CEF_CHANNEL_LAYOUT_STEREO, 44_100, 0));
        assertThrows(IllegalArgumentException.class, () -> new CefAudioParameters(CefChannelLayout.CEF_CHANNEL_LAYOUT_STEREO, 44_100, -1));
        assertThrows(IllegalArgumentException.class, () -> new CefAudioParameters(CefChannelLayout.CEF_CHANNEL_LAYOUT_STEREO, 2_999, 512));
        assertThrows(IllegalArgumentException.class, () -> new CefAudioParameters(CefChannelLayout.CEF_CHANNEL_LAYOUT_STEREO, 768_001, 512));
        assertThrows(IllegalArgumentException.class, () -> new CefAudioParameters(CefChannelLayout.CEF_CHANNEL_LAYOUT_STEREO, 44_100, 768_001));
    }

    @Test
    void settersRejectInvalidValuesWithoutChangingExistingState() {
        CefAudioParameters parameters = new CefAudioParameters(CefChannelLayout.CEF_CHANNEL_LAYOUT_STEREO, 44_100, 512);

        assertThrows(IllegalArgumentException.class, () -> parameters.setChannelLayout(null));
        assertThrows(IllegalArgumentException.class, () -> parameters.setChannelLayout(CefChannelLayout.CEF_CHANNEL_LAYOUT_MAX));
        assertThrows(IllegalArgumentException.class, () -> parameters.setSampleRate(0));
        assertThrows(IllegalArgumentException.class, () -> parameters.setFramesPerBuffer(-1));

        assertSame(CefChannelLayout.CEF_CHANNEL_LAYOUT_STEREO, parameters.channelLayout);
        assertEquals(44_100, parameters.sampleRate);
        assertEquals(512, parameters.framesPerBuffer);
        assertTrue(parameters.isValid());
    }

    @Test
    void acceptsChromiumValidationBoundaries() {
        CefAudioParameters minimum = new CefAudioParameters(CefChannelLayout.CEF_CHANNEL_LAYOUT_MONO, 3_000, 1);
        CefAudioParameters maximum = new CefAudioParameters(CefChannelLayout.CEF_CHANNEL_LAYOUT_7_1_4, 768_000, 768_000);

        assertTrue(minimum.isValid());
        assertTrue(maximum.isValid());
        minimum.validate();
        maximum.validate();
    }

    @Test
    void validatesDirectFieldMutationsRetainedForCompatibility() {
        CefAudioParameters parameters = new CefAudioParameters(CefChannelLayout.CEF_CHANNEL_LAYOUT_STEREO, 44_100, 512);

        parameters.channelLayout = null;
        assertFalse(parameters.isValid());
        assertThrows(IllegalArgumentException.class, parameters::validate);

        parameters.channelLayout = CefChannelLayout.CEF_CHANNEL_LAYOUT_MAX;
        assertFalse(parameters.isValid());
        assertThrows(IllegalArgumentException.class, parameters::validate);

        parameters.channelLayout = CefChannelLayout.CEF_CHANNEL_LAYOUT_STEREO;
        parameters.sampleRate = 0;
        assertFalse(parameters.isValid());
        assertThrows(IllegalArgumentException.class, parameters::validate);

        parameters.sampleRate = 44_100;
        parameters.framesPerBuffer = -1;
        assertFalse(parameters.isValid());
        assertThrows(IllegalArgumentException.class, parameters::validate);
    }
}
