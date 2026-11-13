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
 * Checks that CELT-only Opus decodes to the samples of FFmpeg's native Opus decoder: the same count, each within 1 at
 * 16 bits once rounded as FFmpeg rounds a float, as {@code Mp3DecoderTest} does, and not silence.
 */
class OpusCeltParityTest {

    private static final int TOLERANCE = 1;
    private static final int BYTES_PER_SAMPLE = 2;

    @ParameterizedTest
    @EnumSource(
            value = OpusSample.class,
            names = {
                "CELT_LOWDELAY_MONO_2_5MS",
                "CELT_LOWDELAY_MONO_5MS",
                "CELT_LOWDELAY_MONO_10MS",
                "CELT_LOWDELAY_MONO_20MS",
                "CELT_LOWDELAY_STEREO_2_5MS",
                "CELT_LOWDELAY_STEREO_20MS",
                "CELT_AUDIO_MONO_5MS",
                "CELT_AUDIO_MONO_20MS",
                "CELT_AUDIO_STEREO_20MS",
                "MULTIFRAME_CELT_40MS_CBR",
                "MULTIFRAME_CELT_40MS_VBR",
                "MULTIFRAME_CELT_120MS",
                "SURROUND51"
            })
    void decodesCeltFixturesLikeFfmpeg(final OpusSample sample) throws IOException, InvalidFormatException {
        final var reference = sample.referencePcm().readAllBytes();

        try (final var channel = sample.channel()) {
            assertWithinTolerance(channel, reference);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = FateOpusVector.class,
            names = {"TESTVECTOR01", "TESTVECTOR07", "TESTVECTOR11", "TRON_6CH_TINYPKTS"})
    void decodesCeltFateVectorsLikeFfmpeg(final FateOpusVector vector) throws IOException, InvalidFormatException {
        final var file = FateSamples.opusFile(vector);
        final var reference = Files.readAllBytes(FateSamples.ffmpegPcmOfOpusFile(vector));

        try (final var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            assertWithinTolerance(channel, reference);
        }
    }

    private void assertWithinTolerance(final SeekableByteChannel channel, final byte[] referenceBytes)
            throws IOException, InvalidFormatException {
        final var reference = ByteBuffer.wrap(referenceBytes).order(ByteOrder.LITTLE_ENDIAN);
        final var audio = new OpusParser().parse(channel).audio();
        final var channelCount = audio.streamInfo().channelCount();
        var position = 0;
        var maxError = 0;
        var beyondTolerance = 0L;
        var firstBeyondTolerance = -1;
        var loudest = 0;
        while (audio.hasNextFrame()) {
            final var frame = audio.nextFrame();
            for (var index = 0; index < frame.sampleCount(); index++) {
                for (var frameChannel = 0; frameChannel < channelCount; frameChannel++) {
                    final var decoded = sixteenBits(frame.channelSample(frameChannel, index));
                    final var expected = reference.getShort(position * BYTES_PER_SAMPLE);
                    final var error = Math.abs(decoded - expected);
                    maxError = Math.max(maxError, error);
                    loudest = Math.max(loudest, Math.abs(decoded));
                    if (error > TOLERANCE) {
                        beyondTolerance++;
                        if (firstBeyondTolerance < 0) {
                            firstBeyondTolerance = position / channelCount;
                        }
                    }
                    position++;
                }
            }
        }

        assertThat(position * BYTES_PER_SAMPLE).as("length in bytes").isEqualTo(referenceBytes.length);
        assertThat(loudest).as("loudest sample").isGreaterThan(0);
        assertThat(beyondTolerance)
                .as("samples beyond tolerance (max error %d, first at sample %d)", maxError, firstBeyondTolerance)
                .isZero();
    }

    // As FFmpeg rounds a float sample to 16 bits: to the nearest, half to even, and clipped.
    private static int sixteenBits(final float sample) {
        return (int) Math.clamp(Math.rint(sample * 32768.0), Short.MIN_VALUE, Short.MAX_VALUE);
    }
}
