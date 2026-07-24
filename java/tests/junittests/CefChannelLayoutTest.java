// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.cef.misc.CefChannelLayout;
import org.junit.jupiter.api.Test;

class CefChannelLayoutTest {
    @Test
    void mapsEveryCef151LayoutIdExplicitly() {
        CefChannelLayout[] expected = {
            CefChannelLayout.CEF_CHANNEL_LAYOUT_NONE,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_UNSUPPORTED,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_MONO,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_STEREO,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_2_1,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_SURROUND,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_4_0,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_2_2,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_QUAD,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_5_0,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_5_1,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_5_0_BACK,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_5_1_BACK,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_7_0,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_7_1,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_7_1_WIDE,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_STEREO_DOWNMIX,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_2POINT1,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_3_1,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_4_1,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_6_0,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_6_0_FRONT,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_HEXAGONAL,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_6_1,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_6_1_BACK,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_6_1_FRONT,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_7_0_FRONT,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_7_1_WIDE_BACK,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_OCTAGONAL,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_DISCRETE,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_STEREO_AND_KEYBOARD_MIC,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_4_1_QUAD_SIDE,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_BITSTREAM,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_5_1_4_DOWNMIX,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_1_1,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_3_1_BACK,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_5_1_4,
            CefChannelLayout.CEF_CHANNEL_LAYOUT_7_1_4
        };

        assertEquals(38, expected.length);
        for (int id = 0; id < expected.length; id++) {
            assertSame(expected[id], CefChannelLayout.forId(id));
            assertEquals(id, expected[id].getId());
        }
    }

    @Test
    void mapsUnknownIdsToUnsupportedDeterministically() {
        assertSame(CefChannelLayout.CEF_CHANNEL_LAYOUT_UNSUPPORTED, CefChannelLayout.forId(-1));
        assertSame(CefChannelLayout.CEF_CHANNEL_LAYOUT_UNSUPPORTED, CefChannelLayout.forId(38));
        assertSame(CefChannelLayout.CEF_CHANNEL_LAYOUT_UNSUPPORTED, CefChannelLayout.forId(Integer.MIN_VALUE));
        assertSame(CefChannelLayout.CEF_CHANNEL_LAYOUT_UNSUPPORTED, CefChannelLayout.forId(Integer.MAX_VALUE));
    }

    @Test
    void maxSentinelDoesNotShadowLargestRealLayout() {
        assertEquals(CefChannelLayout.CEF_CHANNEL_LAYOUT_7_1_4.getId(), CefChannelLayout.CEF_CHANNEL_LAYOUT_MAX.getId());
        assertSame(CefChannelLayout.CEF_CHANNEL_LAYOUT_7_1_4, CefChannelLayout.forId(CefChannelLayout.CEF_CHANNEL_LAYOUT_MAX.getId()));
    }
}
