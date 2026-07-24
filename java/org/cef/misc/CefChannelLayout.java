package org.cef.misc;

/**
 * Enumerates CEF's audio channel layouts. The numeric identifiers are part of the JNI contract
 * and must remain synchronized with {@code cef_channel_layout_t}.
 */
public enum CefChannelLayout {
    CEF_CHANNEL_LAYOUT_NONE(0),

    CEF_CHANNEL_LAYOUT_UNSUPPORTED(1),

    /// Front C
    CEF_CHANNEL_LAYOUT_MONO(2),

    /// Front L, Front R
    CEF_CHANNEL_LAYOUT_STEREO(3),

    /// Front L, Front R, Back C
    CEF_CHANNEL_LAYOUT_2_1(4),

    /// Front L, Front R, Front C
    CEF_CHANNEL_LAYOUT_SURROUND(5),

    /// Front L, Front R, Front C, Back C
    CEF_CHANNEL_LAYOUT_4_0(6),

    /// Front L, Front R, Side L, Side R
    CEF_CHANNEL_LAYOUT_2_2(7),

    /// Front L, Front R, Back L, Back R
    CEF_CHANNEL_LAYOUT_QUAD(8),

    /// Front L, Front R, Front C, Side L, Side R
    CEF_CHANNEL_LAYOUT_5_0(9),

    /// Front L, Front R, Front C, LFE, Side L, Side R
    CEF_CHANNEL_LAYOUT_5_1(10),

    /// Front L, Front R, Front C, Back L, Back R
    CEF_CHANNEL_LAYOUT_5_0_BACK(11),

    /// Front L, Front R, Front C, LFE, Back L, Back R
    CEF_CHANNEL_LAYOUT_5_1_BACK(12),

    /// Front L, Front R, Front C, Side L, Side R, Back L, Back R
    CEF_CHANNEL_LAYOUT_7_0(13),

    /// Front L, Front R, Front C, LFE, Side L, Side R, Back L, Back R
    CEF_CHANNEL_LAYOUT_7_1(14),

    /// Front L, Front R, Front C, LFE, Side L, Side R, Front LofC, Front RofC
    CEF_CHANNEL_LAYOUT_7_1_WIDE(15),

    /// Stereo L, Stereo R
    CEF_CHANNEL_LAYOUT_STEREO_DOWNMIX(16),

    /// Stereo L, Stereo R, LFE
    CEF_CHANNEL_LAYOUT_2POINT1(17),

    /// Stereo L, Stereo R, Front C, LFE
    CEF_CHANNEL_LAYOUT_3_1(18),

    /// Stereo L, Stereo R, Front C, Rear C, LFE
    CEF_CHANNEL_LAYOUT_4_1(19),

    /// Stereo L, Stereo R, Front C, Side L, Side R, Back C
    CEF_CHANNEL_LAYOUT_6_0(20),

    /// Stereo L, Stereo R, Side L, Side R, Front LofC, Front RofC
    CEF_CHANNEL_LAYOUT_6_0_FRONT(21),

    /// Stereo L, Stereo R, Front C, Rear L, Rear R, Rear C
    CEF_CHANNEL_LAYOUT_HEXAGONAL(22),

    /// Stereo L, Stereo R, Front C, LFE, Side L, Side R, Rear Center
    CEF_CHANNEL_LAYOUT_6_1(23),

    /// Stereo L, Stereo R, Front C, LFE, Back L, Back R, Rear Center
    CEF_CHANNEL_LAYOUT_6_1_BACK(24),

    /// Stereo L, Stereo R, Side L, Side R, Front LofC, Front RofC, LFE
    CEF_CHANNEL_LAYOUT_6_1_FRONT(25),

    /// Front L, Front R, Front C, Side L, Side R, Front LofC, Front RofC
    CEF_CHANNEL_LAYOUT_7_0_FRONT(26),

    /// Front L, Front R, Front C, LFE, Back L, Back R, Front LofC, Front RofC
    CEF_CHANNEL_LAYOUT_7_1_WIDE_BACK(27),

    /// Front L, Front R, Front C, Side L, Side R, Rear L, Back R, Back C.
    CEF_CHANNEL_LAYOUT_OCTAGONAL(28),

    /// Channels are not explicitly mapped to speakers.
    CEF_CHANNEL_LAYOUT_DISCRETE(29),

    /// Front L, Front R, Front C. Front C contains the keyboard mic audio. This
    /// layout is only intended for input for WebRTC. The Front C channel
    /// is stripped away in the WebRTC audio input pipeline and never seen outside
    /// of that.
    CEF_CHANNEL_LAYOUT_STEREO_AND_KEYBOARD_MIC(30),

    /// Front L, Front R, Side L, Side R, LFE
    CEF_CHANNEL_LAYOUT_4_1_QUAD_SIDE(31),

    /// Actual channel layout is specified in the bitstream and the actual channel
    /// count is unknown at Chromium media pipeline level (useful for audio
    /// pass-through mode).
    CEF_CHANNEL_LAYOUT_BITSTREAM(32),

    /// Front L, Front R, Front C, LFE, Side L, Side R,
    /// Front Height L, Front Height R, Rear Height L, Rear Height R
    /// Will be represented as six channels (5.1) due to eight channel limit
    /// kMaxConcurrentChannels
    CEF_CHANNEL_LAYOUT_5_1_4_DOWNMIX(33),

    /// Front C, LFE
    CEF_CHANNEL_LAYOUT_1_1(34),

    /// Front L, Front R, LFE, Back C
    CEF_CHANNEL_LAYOUT_3_1_BACK(35),

    /// Front L, Front R, Front C, LFE, Side L, Side R,
    /// Top Front L, Top Front R, Top Back L, Top Back R
    CEF_CHANNEL_LAYOUT_5_1_4(36),

    /// Front L, Front R, Front C, LFE, Back L, Back R, Side L, Side R,
    /// Top Front L, Top Front R, Top Back L, Top Back R
    CEF_CHANNEL_LAYOUT_7_1_4(37),

    /** Compatibility sentinel whose ID always matches the largest real layout ID. */
    CEF_CHANNEL_LAYOUT_MAX(37);

    private static final CefChannelLayout[] LAYOUTS_BY_ID = createLayoutsById();

    private final int id;

    CefChannelLayout(int id) {
        this.id = id;
    }

    /** Returns the numeric CEF identifier used by the native bridge. */
    public int getId() {
        return id;
    }

    /**
     * Returns the layout for {@code id}. Unknown current or future IDs map deterministically to
     * {@link #CEF_CHANNEL_LAYOUT_UNSUPPORTED} instead of relying on enum declaration order.
     */
    public static CefChannelLayout forId(int id) {
        if (id < 0 || id >= LAYOUTS_BY_ID.length) return CEF_CHANNEL_LAYOUT_UNSUPPORTED;
        return LAYOUTS_BY_ID[id];
    }

    boolean isConfigurable() {
        // CEF's audio parameter callback exposes a layout but no independent channel count.
        // Chromium therefore cannot construct DISCRETE or BITSTREAM parameters, whose layouts
        // intentionally resolve to zero channels unless a separate count/bitstream format exists.
        return id > CEF_CHANNEL_LAYOUT_UNSUPPORTED.id && this != CEF_CHANNEL_LAYOUT_DISCRETE && this != CEF_CHANNEL_LAYOUT_BITSTREAM && this != CEF_CHANNEL_LAYOUT_MAX && forId(id) == this;
    }

    private static CefChannelLayout[] createLayoutsById() {
        CefChannelLayout[] layouts = new CefChannelLayout[CEF_CHANNEL_LAYOUT_MAX.id + 1];
        for (CefChannelLayout layout : values()) {
            if (layout == CEF_CHANNEL_LAYOUT_MAX) continue;
            if (layout.id < 0 || layout.id >= layouts.length || layouts[layout.id] != null) {
                throw new ExceptionInInitializerError("Duplicate or invalid CEF channel layout ID: " + layout.id);
            }
            layouts[layout.id] = layout;
        }
        for (int id = 0; id < layouts.length; id++) {
            if (layouts[id] == null) layouts[id] = CEF_CHANNEL_LAYOUT_UNSUPPORTED;
        }
        return layouts;
    }
}
