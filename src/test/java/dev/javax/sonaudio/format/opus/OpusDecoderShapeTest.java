package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.samples.FateOpusVector;
import dev.javax.sonaudio.samples.FateSamples;
import dev.javax.sonaudio.samples.OpusSample;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Checks that the decoder, whose SILK and CELT decoding isn't written yet, still gives as many samples in as many
 * channels as FFmpeg does: every sample is silence until then.
 */
class OpusDecoderShapeTest {

    private static final int BYTES_PER_SAMPLE = 2;

    @ParameterizedTest
    @EnumSource(OpusSample.class)
    void decodesAsManySamplesAsFfmpegDoes(final OpusSample sample) throws IOException, InvalidFormatException {
        final var referenceBytes = sample.referencePcm().readAllBytes().length;

        try (final var channel = sample.channel()) {
            assertSameShape(channel, referenceBytes);
        }
    }

    @ParameterizedTest
    @EnumSource(FateOpusVector.class)
    void decodesAsManySamplesAsFfmpegDoesForFateVectors(final FateOpusVector vector)
            throws IOException, InvalidFormatException {
        final var file = FateSamples.opusFile(vector);
        final var referenceBytes = Files.size(FateSamples.ffmpegPcmOfOpusFile(vector));

        try (final var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            assertSameShape(channel, referenceBytes);
        }
    }

    private void assertSameShape(final SeekableByteChannel channel, final long referenceBytes)
            throws IOException, InvalidFormatException {
        final var audio = new OpusParser().parse(channel).audio();
        final var channelCount = audio.streamInfo().channelCount();
        var samples = 0L;
        while (audio.hasNextFrame()) {
            final var frame = audio.nextFrame();
            assertThat(frame.channelCount()).isEqualTo(channelCount);
            samples += frame.sampleCount();
        }

        assertThat(samples).isEqualTo(referenceBytes / BYTES_PER_SAMPLE / channelCount);
    }
}
