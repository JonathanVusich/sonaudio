package dev.javax.sonaudio.format;

/**
 * A block of decoded audio as signed integers, as a lossless decoder such as FLAC's makes it.
 */
public final class IntPcmFrame implements PcmFrame {

    private final int[][] samples;
    private final int sampleRate;

    /**
     * @param samples one array per channel, all the same length, which the frame keeps: the caller makes them for it
     *     and doesn't touch them again
     * @param sampleRate samples per second, per channel
     */
    public IntPcmFrame(final int[][] samples, final int sampleRate) {
        this.samples = samples;
        this.sampleRate = sampleRate;
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public int channelCount() {
        return samples.length;
    }

    @Override
    public int sampleCount() {
        return samples[0].length;
    }

    public int channelSample(final int channel, final int index) {
        return samples[channel][index];
    }
}
