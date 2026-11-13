package dev.javax.sonaudio.format.alac;

import static org.assertj.core.api.Assertions.assertThat;

import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.fingerprint.Fingerprint;
import dev.javax.sonaudio.samples.FateAlacCase;
import dev.javax.sonaudio.samples.FateSamples;
import dev.javax.sonaudio.samples.FingerprintAudioSample;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
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

    // The file's edit list starts in the middle of its second packet, which drops the first. FFmpeg's command line,
    // which decodes with several threads, then loses the 1904 samples of the second packet that it should skip, so its
    // output starts 1904 samples earlier in the audio (and has as many samples, as the edit's duration ends it).
    // libavcodec does not lose them when it decodes in one thread, as fpcalc does. This is fpcalc's fingerprint of the
    // bytes that this builds, made from the chords sample with the same edit list: the same as that of ffmpeg -threads
    // 1's output, and not as that of its output with its default threads.
    @Test
    void fingerprintsAFileWhoseEditListDropsItsFirstPacketAsFpcalcDoes() throws IOException, InvalidFormatException {
        final var sample = FingerprintAudioSample.ALAC_CHORDS_8000HZ_MONO;
        final var bytes = sample.inputStream().readAllBytes();
        final var edited = AlacTestFiles.withEditList(bytes, new long[][] {{150000, 6000}});

        final var fingerprint = new Fingerprint(
                new AlacParser().parse(new SeekableInMemoryByteChannel(edited)).audio());

        assertThat(fingerprint.compressed()).isEqualTo(editListFingerprint());
    }

    private static String editListFingerprint() throws IOException {
        final var resource = "/dev/javax/sonaudio/fingerprint/alac_chords_8000hz_mono_edit_list.txt";
        try (final var inputStream = AlacFingerprintTest.class.getResourceAsStream(resource)) {
            return new String(inputStream.readAllBytes(), StandardCharsets.US_ASCII).trim();
        }
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
