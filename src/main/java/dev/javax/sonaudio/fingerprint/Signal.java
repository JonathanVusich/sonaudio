package dev.javax.sonaudio.fingerprint;

import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.FloatPcmAudio;
import dev.javax.sonaudio.format.IntPcmAudio;
import java.io.IOException;

/**
 * The start of a file's audio as {@code fpcalc} reads it: 16-bit mono samples at 11025 Hz, the first two minutes at
 * most. It brings the audio there with the filter FFmpeg's libswresample uses for {@code fpcalc} (see
 * {@link Resampler}), so the samples match {@code fpcalc}'s to within rounding.
 */
final class Signal {

    public static final int SAMPLE_RATE = 11025;

    private static final int MAX_SAMPLES = 120 * SAMPLE_RATE;

    private final int[] samples;

    /**
     * Decodes only as much of the audio as it needs.
     *
     * @throws IOException when the audio can't be read, or ends before a frame does
     * @throws InvalidFormatException when the audio is corrupt
     */
    public Signal(final IntPcmAudio audio) throws IOException, InvalidFormatException {
        this.samples = new IntSignalReader(MAX_SAMPLES).readSignal(audio);
    }

    /**
     * Decodes only as much of the audio as it needs, and stops where its decoder dropped a frame it couldn't decode.
     *
     * @throws IOException when the audio can't be read, or ends before a frame does
     * @throws InvalidFormatException when the audio is corrupt
     */
    public Signal(final FloatPcmAudio audio) throws IOException, InvalidFormatException {
        this.samples = new FloatSignalReader(MAX_SAMPLES).readSignal(audio);
    }

    public int sampleCount() {
        return samples.length;
    }

    public int sample(final int index) {
        return samples[index];
    }
}
