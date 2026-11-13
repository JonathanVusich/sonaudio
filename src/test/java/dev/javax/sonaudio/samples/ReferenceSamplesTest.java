package dev.javax.sonaudio.samples;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Checks that every constant of the sample enums has its files, and that the FATE samples are complete when they're
 * prepared (the latter is skipped otherwise, as on CI).
 */
class ReferenceSamplesTest {

    @ParameterizedTest
    @EnumSource(AlacSample.class)
    void everyAlacSampleHasItsFileAndReference(final AlacSample sample) throws IOException {
        try (final var file = sample.inputStream();
                final var reference = sample.referencePcm()) {
            assertThat(file.readAllBytes()).hasSizeGreaterThan(1000);
            assertThat(reference.readAllBytes()).hasSizeGreaterThan(1000);
        }
    }

    @ParameterizedTest
    @EnumSource(OpusSample.class)
    void everyOpusSampleHasItsFileAndReference(final OpusSample sample) throws IOException {
        try (final var file = sample.inputStream();
                final var reference = sample.referencePcm()) {
            assertThat(file.readAllBytes()).startsWith("OggS".getBytes());
            assertThat(reference.readAllBytes()).hasSizeGreaterThan(1000);
        }
    }

    @ParameterizedTest
    @EnumSource(FingerprintAudioSample.class)
    void everyFingerprintSampleHasItsFileAndFingerprint(final FingerprintAudioSample sample) throws IOException {
        try (final var file = sample.inputStream()) {
            assertThat(file.readAllBytes()).hasSizeGreaterThan(1000);
        }
        assertThat(sample.fpcalcFingerprint()).startsWith("AQAA");
    }

    @ParameterizedTest
    @EnumSource(FateOpusVector.class)
    void everyPreparedFateOpusVectorHasItsFiles(final FateOpusVector vector) {
        assertThat(FateSamples.matroskaFile(vector)).isRegularFile();
        assertThat(FateSamples.opusFile(vector)).isRegularFile();
        assertThat(FateSamples.ffmpegPcm(vector)).isRegularFile();
        assertThat(FateSamples.ffmpegPcmOfOpusFile(vector)).isRegularFile();
        assertThat(FateSamples.rfcDecoded(vector)).isRegularFile();
    }

    @ParameterizedTest
    @EnumSource(FateAlacCase.class)
    void everyPreparedFateAlacCaseHasItsFiles(final FateAlacCase fateCase) throws IOException {
        assertThat(FateSamples.alacFile(fateCase)).isRegularFile();
        assertThat(Files.size(FateSamples.alacReferencePcm(fateCase))).isPositive();
    }

    @Test
    void preparedInsideFileDecodesToFatesMd5Reference() throws IOException, NoSuchAlgorithmException {
        final var samples = Files.readAllBytes(FateSamples.insidePcm());

        final var md5 = MessageDigest.getInstance("MD5").digest(samples);

        assertThat(HexFormat.of().formatHex(md5)).isEqualTo(FateSamples.INSIDE_M4A_MD5);
        assertThat(FateSamples.insideFile()).isRegularFile();
    }
}
