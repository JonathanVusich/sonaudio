package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.fingerprint.Fingerprint;
import dev.javax.sonaudio.samples.FateOpusVector;
import dev.javax.sonaudio.samples.FateSamples;
import dev.javax.sonaudio.samples.FingerprintAudioSample;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Fingerprints the decoded audio, which {@code fpcalc} does with the audio that FFmpeg decodes, so the same decoded
 * samples make the same fingerprint. The references are {@code fpcalc}'s output (Chromaprint 1.6.2).
 */
class OpusFingerprintTest {

    @ParameterizedTest
    @EnumSource(
            value = FingerprintAudioSample.class,
            names = {"OPUS_CHORDS_48000HZ_STEREO", "OPUS_NOISE_48000HZ_1CH"})
    void fingerprintsOpusAudioAsFpcalcDoes(final FingerprintAudioSample sample)
            throws IOException, InvalidFormatException {
        try (final var channel = sample.channel()) {
            final var fingerprint =
                    new Fingerprint(new OpusParser().parse(channel).audio());

            assertThat(fingerprint.compressed())
                    .isEqualTo(sample.fpcalcFingerprint().trim());
        }
    }

    // silk-lbrr and silk-lbrr-mono are too short for fpcalc to fingerprint, so they have no output.
    @ParameterizedTest
    @EnumSource(
            value = FateOpusVector.class,
            mode = EnumSource.Mode.EXCLUDE,
            names = {"SILK_LBRR", "SILK_LBRR_MONO"})
    void fingerprintsFateVectorsAsFpcalcDoes(final FateOpusVector vector) throws IOException, InvalidFormatException {
        final var file = FateSamples.opusFile(vector);

        try (final var channel = Files.newByteChannel(file)) {
            final var fingerprint =
                    new Fingerprint(new OpusParser().parse(channel).audio());

            assertThat(fingerprint.compressed())
                    .isEqualTo(fpcalcFingerprint(
                            FateSamples.fpcalcOutput(file.getFileName().toString())));
        }
    }

    private static String fpcalcFingerprint(final Path fpcalcOutput) throws IOException {
        return Files.readAllLines(fpcalcOutput).stream()
                .filter(line -> line.startsWith("FINGERPRINT="))
                .map(line -> line.substring("FINGERPRINT=".length()).trim())
                .findFirst()
                .orElseThrow(() -> new AssertionError("No fingerprint in " + fpcalcOutput));
    }
}
