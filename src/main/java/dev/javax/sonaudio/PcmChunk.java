package dev.javax.sonaudio;

/**
 * A block of decoded mono audio as 16-bit samples, which never changes once it's made.
 */
public final class PcmChunk {

    private final short[] samples;

    /**
     * @param samples the samples, which the chunk keeps: the caller makes them for it and doesn't touch them again
     */
    PcmChunk(final short[] samples) {
        this.samples = samples;
    }

    public int sampleCount() {
        return samples.length;
    }

    public short sample(final int index) {
        return samples[index];
    }
}
