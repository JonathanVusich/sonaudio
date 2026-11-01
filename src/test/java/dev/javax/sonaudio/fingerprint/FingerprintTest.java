package dev.javax.sonaudio.fingerprint;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.IntPcmAudio;
import dev.javax.sonaudio.format.IntPcmFrame;

/**
 * Compares fingerprints with those {@code fpcalc} (Chromaprint 1.5.1) computed from the same audio, which the
 * reference files under {@code dev/javax/sonaudio/fingerprint} hold.
 */
class FingerprintTest {

    @Test
    void matchesFpcalcWhenNothingIsResampled() throws IOException, InvalidFormatException {
        final var audio = chordAudio(11025, 1, 16, 30);

        final var fingerprint = new Fingerprint(audio);

        assertThat(fingerprint.compressed()).isEqualTo(reference("chords_11025hz_mono"));
    }

    @Test
    void matchesFpcalcForSilence() throws IOException, InvalidFormatException {
        final var audio = silentAudio(44100, 2, 10);

        final var fingerprint = new Fingerprint(audio);

        assertThat(fingerprint.compressed()).isEqualTo(reference("silence_44100hz_stereo"));
    }

    @ParameterizedTest
    @CsvSource({
        "44100, 16, chords_44100hz_stereo",
        "48000, 16, chords_48000hz_stereo",
        // The same audio as at 16 bits, shifted up, so it comes back the same once brought to 16 bits.
        "48000, 24, chords_48000hz_stereo"
    })
    void matchesFpcalcWhenResampled(final int sampleRate, final int bitsPerSample, final String referenceName)
            throws IOException, InvalidFormatException {
        final var audio = chordAudio(sampleRate, 2, bitsPerSample, 30);

        final var fingerprint = new Fingerprint(audio);

        assertThat(fingerprint.compressed()).isEqualTo(reference(referenceName));
    }

    // Chords under noise, which make rounding in the resampler matter, unlike the chords alone. The rates take the
    // resampler
    // through its filters with one phase, with 147 and with 256, with and without interpolation between phases.
    @ParameterizedTest
    @CsvSource({"44100, 2", "48000, 2", "32000, 2", "96000, 2", "8000, 1"})
    void matchesFpcalcForNoise(final int sampleRate, final int channels) throws IOException, InvalidFormatException {
        final var audio = noiseAudio(sampleRate, channels, 25);

        final var fingerprint = new Fingerprint(audio);

        assertThat(fingerprint.compressed()).isEqualTo(reference("noise_%dhz_%dch".formatted(sampleRate, channels)));
    }

    @Test
    void audioShorterThanOneFrameHasAnEmptyFingerprint() throws IOException, InvalidFormatException {
        final var audio = silentAudio(44100, 2, 0);

        final var fingerprint = new Fingerprint(audio);

        assertThat(fingerprint.compressed()).isEqualTo("AQAAAA");
    }

    private static String reference(final String name) throws IOException {
        try (final var inputStream = FingerprintTest.class.getResourceAsStream(name + ".txt")) {
            return new String(inputStream.readAllBytes(), StandardCharsets.US_ASCII);
        }
    }

    /**
     * Chords of three notes that change every 0.4 seconds, picked by a linear congruential generator: the audio
     * {@code gen.py} wrote for {@code fpcalc}, generated the same way.
     */
    private static IntPcmAudio chordAudio(
            final int sampleRate, final int channels, final int bitsPerSample, final int seconds) {
        return new GeneratedAudio(
                sampleRate,
                bitsPerSample,
                channels,
                sampleRate * seconds,
                (index, channel) -> chordSample(sampleRate, bitsPerSample, index, channel));
    }

    private static int chordSample(final int sampleRate, final int bitsPerSample, final int index, final int channel) {
        final var chord = (long) index * 5 / (2L * sampleRate);
        final var seed = (chord * 1103515245L + 12345) % (1L << 31);
        var total = 0.0;
        for (var note = 0; note < 3; note++) {
            final var pitch = 48 + ((seed >> (8 * note)) % 36);
            final var frequency = 440.0 * Math.pow(2, (pitch - 69) / 12.0);
            total += Math.sin(2 * Math.PI * frequency * index / sampleRate + channel);
        }
        return (int) Math.round(8000 * total) << (bitsPerSample - 16);
    }

    // Chords as in chordAudio under noise that is a hash of each sample's position, so the audio can be made again
    // anywhere and {@code fpcalc} can be given it.
    private static IntPcmAudio noiseAudio(final int sampleRate, final int channels, final int seconds) {
        return new GeneratedAudio(
                sampleRate,
                16,
                channels,
                sampleRate * seconds,
                (index, channel) -> noiseSample(sampleRate, index, channel));
    }

    private static int noiseSample(final int sampleRate, final int index, final int channel) {
        final var chord = (long) index * 5 / (2L * sampleRate);
        final var seed = hash(chord * 3);
        var total = 0.0;
        for (var note = 0; note < 3; note++) {
            final var pitch = 48 + ((seed >>> (8 * note)) & 0xFF) % 36;
            final var frequency = 440.0 * Math.pow(2, (pitch - 69) / 12.0);
            total += Math.sin(2 * Math.PI * frequency * index / sampleRate + channel);
        }
        final var noise = (int) (hash(2L * index + channel) >>> 48) - 32768;
        return (int) Math.round(5000 * total + noise / 16.0);
    }

    private static long hash(final long value) {
        var mixed = value * 0x9E3779B97F4A7C15L;
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        return mixed ^ (mixed >>> 31);
    }

    private static IntPcmAudio silentAudio(final int sampleRate, final int channels, final int seconds) {
        return new GeneratedAudio(sampleRate, 16, channels, sampleRate * seconds, (_, _) -> 0);
    }

    @FunctionalInterface
    private interface SampleSource {
        int sample(int index, int channel);
    }

    private static final class GeneratedAudio implements IntPcmAudio {

        private static final int FRAME_SIZE = 4096;

        private final int sampleRate;
        private final int bitsPerSample;
        private final int channels;
        private final int sampleCount;
        private final SampleSource source;
        private int next;

        GeneratedAudio(
                final int sampleRate,
                final int bitsPerSample,
                final int channels,
                final int sampleCount,
                final SampleSource source) {
            this.sampleRate = sampleRate;
            this.bitsPerSample = bitsPerSample;
            this.channels = channels;
            this.sampleCount = sampleCount;
            this.source = source;
        }

        @Override
        public int sampleRate() {
            return sampleRate;
        }

        @Override
        public int bitsPerSample() {
            return bitsPerSample;
        }

        @Override
        public boolean hasNextFrame() {
            return next < sampleCount;
        }

        @Override
        public IntPcmFrame nextFrame() {
            if (!hasNextFrame()) {
                throw new NoSuchElementException("Audio has no more frames!");
            }
            final var samples = new int[channels][Math.min(FRAME_SIZE, sampleCount - next)];
            for (var i = 0; i < samples[0].length; i++, next++) {
                for (var channel = 0; channel < channels; channel++) {
                    samples[channel][i] = source.sample(next, channel);
                }
            }
            return new IntPcmFrame(samples, sampleRate);
        }
    }
}
