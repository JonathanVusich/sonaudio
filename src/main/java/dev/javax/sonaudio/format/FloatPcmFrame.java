package dev.javax.sonaudio.format;

/**
 * A block of decoded audio as floating-point numbers, where a full-scale sample is 1.0, as a decoder that works in
 * floating point makes it, before they're rounded to integers.
 */
public final class FloatPcmFrame implements PcmFrame {

    private final float[][] samples;
    private final int sampleRate;
    private final FrameContinuity continuity;

    /**
     * @param samples one array per channel, all the same length, which the frame keeps: the caller makes them for it
     *     and doesn't touch them again
     * @param sampleRate samples per second, per channel
     * @param continuity whether the decoder dropped a frame it couldn't decode before this one
     */
    public FloatPcmFrame(final float[][] samples, final int sampleRate, final FrameContinuity continuity) {
        this.samples = samples;
        this.sampleRate = sampleRate;
        this.continuity = continuity;
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * @return whether the decoder dropped a frame it couldn't decode before this one
     */
    public FrameContinuity continuity() {
        return continuity;
    }

    @Override
    public int channelCount() {
        return samples.length;
    }

    @Override
    public int sampleCount() {
        return samples[0].length;
    }

    public float channelSample(final int channel, final int index) {
        return samples[channel][index];
    }
}
