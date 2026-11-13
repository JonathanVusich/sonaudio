package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.samples.FateOpusVector;
import dev.javax.sonaudio.samples.FateSamples;
import dev.javax.sonaudio.samples.OpusSample;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Checks that SILK-only files decode to what FFmpeg's native Opus decoder gives: as many samples, each within 1 of
 * its 16-bit sample once the decoder's floats are rounded to 16 bits as FFmpeg does.
 */
class OpusSilkParityTest {

    private static final int TOLERANCE = 1;
    private static final int BYTES_PER_SAMPLE = 2;

    private record Comparison(
            int decodedSamples, int referenceSamples, int maxError, int differing, int beyondTolerance, int loud) {}

    @ParameterizedTest
    @EnumSource(
            names = {
                "SILK_NB_MONO_20MS",
                "SILK_MB_MONO_20MS",
                "SILK_WB_MONO_20MS",
                "SILK_NB_STEREO_20MS",
                "SILK_MB_STEREO_20MS",
                "SILK_WB_STEREO_20MS",
                "SILK_NB_MONO_10MS",
                "SILK_NB_MONO_40MS",
                "SILK_NB_MONO_60MS",
                "SILK_WB_STEREO_10MS",
                "SILK_WB_STEREO_40MS",
                "SILK_WB_STEREO_60MS",
                "MULTIFRAME_SILK_80MS",
                "OUTPUT_GAIN",
                "TAGGED",
                "R128_PICTURE"
            })
    void decodesSilkFilesAsFfmpegDoes(final OpusSample sample) throws IOException, InvalidFormatException {
        final var reference = sample.referencePcm().readAllBytes();

        try (final var channel = sample.channel()) {
            assertMatches(compare(channel, reference));
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = FateOpusVector.class,
            names = {"TESTVECTOR02", "TESTVECTOR03", "TESTVECTOR04", "SILK_LBRR_MONO"})
    void decodesFateSilkVectorsAsFfmpegDoes(final FateOpusVector vector) throws IOException, InvalidFormatException {
        final var file = FateSamples.opusFile(vector);
        final var reference = Files.readAllBytes(FateSamples.ffmpegPcmOfOpusFile(vector));

        try (final var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            assertMatches(compare(channel, reference));
        }
    }

    private void assertMatches(final Comparison comparison) {
        final var description = comparison.toString();
        assertThat(comparison.decodedSamples()).as(description).isEqualTo(comparison.referenceSamples());
        assertThat(comparison.loud())
                .as("samples that aren't silence: " + description)
                .isPositive();
        assertThat(comparison.beyondTolerance()).as(description).isZero();
    }

    private Comparison compare(final SeekableByteChannel channel, final byte[] referenceBytes)
            throws IOException, InvalidFormatException {
        final var audio = new OpusParser().parse(channel).audio();
        final var channelCount = audio.streamInfo().channelCount();
        final var reference = ByteBuffer.wrap(referenceBytes).order(ByteOrder.LITTLE_ENDIAN);
        var position = 0;
        var maxError = 0;
        var differing = 0;
        var beyondTolerance = 0;
        var loud = 0;
        while (audio.hasNextFrame()) {
            final var frame = audio.nextFrame();
            for (var index = 0; index < frame.sampleCount(); index++) {
                for (var frameChannel = 0; frameChannel < channelCount; frameChannel++) {
                    final var decoded = sixteenBits(frame.channelSample(frameChannel, index));
                    if (decoded != 0) {
                        loud++;
                    }
                    if (position < referenceBytes.length) {
                        final var error = Math.abs(decoded - reference.getShort(position));
                        maxError = Math.max(maxError, error);
                        if (error > 0) {
                            differing++;
                        }
                        if (error > TOLERANCE) {
                            beyondTolerance++;
                        }
                    }
                    position += BYTES_PER_SAMPLE;
                }
            }
        }
        return new Comparison(
                position / BYTES_PER_SAMPLE / channelCount,
                referenceBytes.length / BYTES_PER_SAMPLE / channelCount,
                maxError,
                differing,
                beyondTolerance,
                loud);
    }

    // As FFmpeg rounds a float sample to 16 bits: to the nearest, half to even, and clipped.
    private int sixteenBits(final float sample) {
        return (int) Math.clamp(Math.rint(sample * 32768.0), Short.MIN_VALUE, Short.MAX_VALUE);
    }
}
