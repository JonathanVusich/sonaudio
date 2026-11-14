package dev.javax.sonaudio.samples;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

/**
 * 25 s files of the audio the fingerprint tests use (chords of three notes that change every 0.4 s, alone or under
 * noise: {@code reference/gen-chords.py} writes it as {@code FingerprintTest} does, with a quarter of its noise), in
 * small ALAC and Opus files, with the fingerprint {@code fpcalc} (Chromaprint 1.6.2, built on the FFmpeg that decodes
 * them) prints for each: its {@code FINGERPRINT=} value, which is what the {@code .txt} files under
 * {@code dev/javax/sonaudio/fingerprint} hold. Commands are as in {@link AlacSample} and {@link OpusSample}, run by
 * {@code reference/fixtures.sh}.
 */
public enum FingerprintAudioSample {
    // Chords alone, mono at 8 kHz, 16 bits: -c:a alac -f ipod.
    ALAC_CHORDS_8000HZ_MONO("alac/fingerprint_alac_chords_8000hz_mono.m4a", "alac_chords_8000hz_mono"),
    // Chords under noise, mono at 8 kHz, 16 bits: -c:a alac -f ipod.
    ALAC_NOISE_8000HZ_1CH("alac/fingerprint_alac_noise_8000hz_1ch.m4a", "alac_noise_8000hz_1ch"),
    // Chords alone, stereo at 48 kHz: -c:a libopus -application audio -b:a 16k.
    OPUS_CHORDS_48000HZ_STEREO("opus/fingerprint_opus_chords_48000hz_stereo.opus", "opus_chords_48000hz_stereo"),
    // Chords under noise, mono at 48 kHz: -c:a libopus -application audio -b:a 24k.
    OPUS_NOISE_48000HZ_1CH("opus/fingerprint_opus_noise_48000hz_1ch.opus", "opus_noise_48000hz_1ch");

    private final String path;
    private final String fingerprintName;

    FingerprintAudioSample(final String path, final String fingerprintName) {
        this.path = path;
        this.fingerprintName = fingerprintName;
    }

    public InputStream inputStream() {
        return FingerprintAudioSample.class.getResourceAsStream(path);
    }

    /**
     * @return the file in memory, as parsers read it
     */
    public SeekableByteChannel channel() throws IOException {
        try (final var inputStream = inputStream()) {
            return new SeekableInMemoryByteChannel(inputStream.readAllBytes());
        }
    }

    /**
     * @return the fingerprint {@code fpcalc} printed for the file, as it prints it
     */
    public String fpcalcFingerprint() throws IOException {
        final var resource = "/dev/javax/sonaudio/fingerprint/" + fingerprintName + ".txt";
        try (final var inputStream = FingerprintAudioSample.class.getResourceAsStream(resource)) {
            return new String(inputStream.readAllBytes(), StandardCharsets.US_ASCII);
        }
    }
}
