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
import java.util.ArrayList;
import org.assertj.core.data.Offset;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Checks the decoder against FFmpeg 9.0.2's native Opus decoder, in two ways. Every committed fixture and every FATE
 * vector must decode to as many samples in as many channels as FFmpeg gives, each within 1 of its 16-bit sample once
 * the decoder's floats are rounded to 16 bits as FFmpeg rounds them, and not silence. And each FATE vector must meet
 * FATE's own criterion, which is that the standard deviation of the difference from RFC 8251's decode is within
 * {@link FateSamples#STDDEV_FUZZ} of the vector's target.
 */
class OpusDecoderParityTest {

    private static final int TOLERANCE = 1;
    private static final int BYTES_PER_SAMPLE = 2;

    // tiny_psnr's fixed-point scale: it prints the deviation in hundredths.
    private static final long DEVIATION_SCALE = 100;

    private record Decoded(int channelCount, short[] samples) {}

    @ParameterizedTest
    @EnumSource(OpusSample.class)
    void decodesFixturesAsFfmpegDoes(final OpusSample sample) throws IOException, InvalidFormatException {
        final var reference = sample.referencePcm().readAllBytes();

        try (final var channel = sample.channel()) {
            assertWithinTolerance(channel, reference);
        }
    }

    @ParameterizedTest
    @EnumSource(FateOpusVector.class)
    void decodesFateVectorsAsFfmpegDoes(final FateOpusVector vector) throws IOException, InvalidFormatException {
        final var file = FateSamples.opusFile(vector);
        final var reference = Files.readAllBytes(FateSamples.ffmpegPcmOfOpusFile(vector));

        try (final var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            assertWithinTolerance(channel, reference);
        }
    }

    // FATE's size check compares the file with the Matroska decode's length, but silk-lbrr-mono and tron.6ch.tinypkts
    // are Ogg here, and FFmpeg's own decode of that differs in length (see FateSamples.ffmpegPcmOfOpusFile), which the
    // first test checks. The deviation is over the samples both have, as tiny_psnr's is.
    @ParameterizedTest
    @EnumSource(
            value = FateOpusVector.class,
            mode = EnumSource.Mode.EXCLUDE,
            names = {"SILK_LBRR_MONO"})
    void meetsFatesStandardDeviationTarget(final FateOpusVector vector) throws IOException, InvalidFormatException {
        final var file = FateSamples.opusFile(vector);
        final var rfcReference = Files.readAllBytes(FateSamples.rfcDecoded(vector));
        final var target = vector.cmpTarget().orElseThrow();

        try (final var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            final var decoded = decode(channel);
            final var deviation = deviation(decoded.samples(), rfcReference, vector.cmpShiftBytes());

            assertThat(deviation)
                    .as("%s: standard deviation from the RFC's decode, target %s", vector, target)
                    .isCloseTo(target, Offset.offset((double) FateSamples.STDDEV_FUZZ));
        }
    }

    private void assertWithinTolerance(final SeekableByteChannel channel, final byte[] referenceBytes)
            throws IOException, InvalidFormatException {
        final var decoded = decode(channel);
        final var samples = decoded.samples();
        final var reference = ByteBuffer.wrap(referenceBytes).order(ByteOrder.LITTLE_ENDIAN);
        final var comparedSamples = Math.min(samples.length, referenceBytes.length / BYTES_PER_SAMPLE);
        var maxError = 0;
        var beyondTolerance = 0L;
        var firstBeyondTolerance = -1;
        var loudest = 0;
        for (var position = 0; position < comparedSamples; position++) {
            final var error = Math.abs(samples[position] - reference.getShort(position * BYTES_PER_SAMPLE));
            maxError = Math.max(maxError, error);
            loudest = Math.max(loudest, Math.abs(samples[position]));
            if (error > TOLERANCE) {
                beyondTolerance++;
                if (firstBeyondTolerance < 0) {
                    firstBeyondTolerance = position / decoded.channelCount();
                }
            }
        }

        assertThat(samples.length * BYTES_PER_SAMPLE).as("length in bytes").isEqualTo(referenceBytes.length);
        assertThat(loudest).as("loudest sample").isGreaterThan(0);
        assertThat(beyondTolerance)
                .as("samples beyond tolerance (max error %d, first at sample %d)", maxError, firstBeyondTolerance)
                .isZero();
    }

    private Decoded decode(final SeekableByteChannel channel) throws IOException, InvalidFormatException {
        final var audio = new OpusParser().parse(channel).audio();
        final var channelCount = audio.streamInfo().channelCount();
        final var chunks = new ArrayList<short[]>();
        var total = 0;
        while (audio.hasNextFrame()) {
            final var frame = audio.nextFrame();
            assertThat(frame.channelCount()).isEqualTo(channelCount);
            final var chunk = new short[frame.sampleCount() * channelCount];
            for (var index = 0; index < frame.sampleCount(); index++) {
                for (var frameChannel = 0; frameChannel < channelCount; frameChannel++) {
                    chunk[index * channelCount + frameChannel] =
                            (short) sixteenBits(frame.channelSample(frameChannel, index));
                }
            }
            chunks.add(chunk);
            total += chunk.length;
        }
        final var samples = new short[total];
        var offset = 0;
        for (final var chunk : chunks) {
            System.arraycopy(chunk, 0, samples, offset, chunk.length);
            offset += chunk.length;
        }
        return new Decoded(channelCount, samples);
    }

    // tests/tiny_psnr.c's stddev for 16-bit samples: the reference's first shiftBytes bytes are skipped, and the
    // deviation is over as many samples as both files have after that.
    private double deviation(final short[] decoded, final byte[] referenceBytes, final int shiftBytes) {
        final var reference = ByteBuffer.wrap(referenceBytes).order(ByteOrder.LITTLE_ENDIAN);
        final var referenceSamples = (referenceBytes.length - shiftBytes) / BYTES_PER_SAMPLE;
        final var count = Math.max(1, Math.min(decoded.length, referenceSamples));
        var squaredError = 0L;
        for (var position = 0; position < Math.min(decoded.length, referenceSamples); position++) {
            final var difference = (long) reference.getShort(shiftBytes + position * BYTES_PER_SAMPLE) - decoded[position];
            squaredError += difference * difference;
        }
        final var scaled = squaredError / count * DEVIATION_SCALE * DEVIATION_SCALE
                + (squaredError % count * DEVIATION_SCALE * DEVIATION_SCALE + count / 2) / count;
        return integerSquareRoot(scaled) / (double) DEVIATION_SCALE;
    }

    private long integerSquareRoot(final long value) {
        var root = 0L;
        var rootSquared = 0L;
        for (var shift = 31; shift >= 0; shift--) {
            final var candidate = rootSquared + (1L << (shift * 2)) + (root << shift) * 2;
            if (candidate <= value && candidate >= 0) {
                rootSquared = candidate;
                root += 1L << shift;
            }
        }
        return root;
    }

    // As FFmpeg rounds a float sample to 16 bits: to the nearest, half to even, and clipped.
    private static int sixteenBits(final float sample) {
        return (int) Math.clamp(Math.rint(sample * 32768.0), Short.MIN_VALUE, Short.MAX_VALUE);
    }
}
