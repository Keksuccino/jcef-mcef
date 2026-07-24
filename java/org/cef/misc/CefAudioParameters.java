package org.cef.misc;

/**
 * Mutable audio stream configuration exchanged with CEF.
 *
 * <p>The public fields are retained for source compatibility. Prefer the setters because they
 * reject invalid values immediately. The native bridge validates the complete object after
 * {@code CefAudioHandler.getAudioParameters} returns and copies it back to CEF only when the
 * callback returned {@code true}.
 */
public class CefAudioParameters {
    // Keep these synchronized with media::limits values used by Chromium's
    // AudioParameters::IsValid implementation.
    private static final int MIN_SAMPLE_RATE = 3_000;
    private static final int MAX_SAMPLE_RATE = 768_000;
    private static final int MAX_FRAMES_PER_BUFFER = 768_000;

    public CefChannelLayout channelLayout;
    public int sampleRate;
    public int framesPerBuffer;

    public CefAudioParameters(CefChannelLayout channelLayout, int sampleRate, int framesPerBuffer) {
        requireConfigurableChannelLayout(channelLayout);
        requireSampleRate(sampleRate);
        requireFramesPerBuffer(framesPerBuffer);
        this.channelLayout = channelLayout;
        this.sampleRate = sampleRate;
        this.framesPerBuffer = framesPerBuffer;
    }

    /** Native snapshots may represent a future layout as UNSUPPORTED for forward compatibility. */
    private CefAudioParameters(CefChannelLayout channelLayout, int sampleRate, int framesPerBuffer, boolean nativeSnapshot) {
        if (!nativeSnapshot) throw new IllegalArgumentException("This constructor is reserved for native snapshots");
        if (channelLayout == null) throw new IllegalArgumentException("channelLayout must not be null");
        this.channelLayout = channelLayout;
        this.sampleRate = sampleRate;
        this.framesPerBuffer = framesPerBuffer;
    }

    public CefChannelLayout getChannelLayout() {
        return channelLayout;
    }

    public void setChannelLayout(CefChannelLayout channelLayout) {
        requireConfigurableChannelLayout(channelLayout);
        this.channelLayout = channelLayout;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(int sampleRate) {
        requireSampleRate(sampleRate);
        this.sampleRate = sampleRate;
    }

    public int getFramesPerBuffer() {
        return framesPerBuffer;
    }

    public void setFramesPerBuffer(int framesPerBuffer) {
        requireFramesPerBuffer(framesPerBuffer);
        this.framesPerBuffer = framesPerBuffer;
    }

    /** Returns whether all fields can be copied safely into CEF's native parameter structure. */
    public boolean isValid() {
        boolean validLayout = channelLayout != null && channelLayout.isConfigurable();
        boolean validSampleRate = sampleRate >= MIN_SAMPLE_RATE && sampleRate <= MAX_SAMPLE_RATE;
        boolean validBuffer = framesPerBuffer > 0 && framesPerBuffer <= MAX_FRAMES_PER_BUFFER;
        return validLayout && validSampleRate && validBuffer;
    }

    /** Validates direct field mutations retained for source compatibility. */
    public void validate() {
        requireConfigurableChannelLayout(channelLayout);
        requireSampleRate(sampleRate);
        requireFramesPerBuffer(framesPerBuffer);
    }

    private static void requireConfigurableChannelLayout(CefChannelLayout channelLayout) {
        if (channelLayout == null) throw new IllegalArgumentException("channelLayout must not be null");
        if (!channelLayout.isConfigurable()) throw new IllegalArgumentException("channelLayout must identify playable channels");
    }

    private static void requireSampleRate(int sampleRate) {
        if (sampleRate < MIN_SAMPLE_RATE || sampleRate > MAX_SAMPLE_RATE) throw new IllegalArgumentException("sampleRate is outside Chromium's supported range");
    }

    private static void requireFramesPerBuffer(int framesPerBuffer) {
        if (framesPerBuffer <= 0 || framesPerBuffer > MAX_FRAMES_PER_BUFFER) throw new IllegalArgumentException("framesPerBuffer is outside Chromium's supported range");
    }
}
