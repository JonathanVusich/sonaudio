package dev.javax.sonaudio.format.alac;

import static org.assertj.core.api.Assertions.assertThat;

import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.fingerprint.Fingerprint;
import dev.javax.sonaudio.samples.FateAlacCase;
import dev.javax.sonaudio.samples.FateSamples;
import dev.javax.sonaudio.samples.FingerprintAudioSample;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Fingerprints the decoded audio, which {@code fpcalc} does with the audio that FFmpeg decodes, so the same decoded
 * samples make the same fingerprint. The references are {@code fpcalc}'s output (Chromaprint 1.6.2).
 */
class AlacFingerprintTest {

    @ParameterizedTest
    @EnumSource(
            value = FingerprintAudioSample.class,
            names = {"ALAC_CHORDS_8000HZ_MONO", "ALAC_NOISE_8000HZ_1CH"})
    void fingerprintsAlacAudioAsFpcalcDoes(final FingerprintAudioSample sample)
            throws IOException, InvalidFormatException {
        try (final var channel = sample.channel()) {
            final var fingerprint =
                    new Fingerprint(new AlacParser().parse(channel).audio());

            assertThat(fingerprint.compressed())
                    .isEqualTo(sample.fpcalcFingerprint().trim());
        }
    }

    @ParameterizedTest
    @EnumSource(FateAlacCase.class)
    void fingerprintsFateEncodesAsFpcalcDoes(final FateAlacCase fateCase) throws IOException, InvalidFormatException {
        final var file = FateSamples.alacFile(fateCase);

        assertSameFingerprint(file, FateSamples.fpcalcOutput(file.getFileName().toString()));
    }

    @Test
    void fingerprintsInsideM4aAsFpcalcDoes() throws IOException, InvalidFormatException {
        assertSameFingerprint(FateSamples.insideFile(), FateSamples.fpcalcOutput("inside.m4a"));
    }

    private static void assertSameFingerprint(final Path file, final Path fpcalcOutput)
            throws IOException, InvalidFormatException {
        try (final var channel = Files.newByteChannel(file)) {
            final var fingerprint =
                    new Fingerprint(new AlacParser().parse(channel).audio());

            assertThat(fingerprint.compressed()).isEqualTo(fpcalcFingerprint(fpcalcOutput));
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
