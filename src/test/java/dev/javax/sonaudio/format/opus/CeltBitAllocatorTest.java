package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CeltBitAllocatorTest {

    private static RangeDecoder decoderOf(final long seed, final int bytes) {
        final var frame = new byte[bytes];
        new Random(seed).nextBytes(frame);
        final var decoder = new RangeDecoder();
        decoder.initialise(frame, 0, bytes);
        return decoder;
    }

    private static CeltFrame frameOf(final int sizeShift, final int channels, final int bytes) {
        return new CeltFrame(sizeShift, channels, 0, 21, bytes * 8, CeltBlockMode.LONG);
    }

    // Whatever the frame's bits say, what the bands are given never exceeds what the frame has, nor is negative.
    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    void neverAllocatesMoreBitsThanTheFrameHas(final long seed) {
        for (final var channels : new int[] {1, 2}) {
            for (var sizeShift = 0; sizeShift < 4; sizeShift++) {
                for (final var bytes : new int[] {20, 80, 300}) {
                    assertAllocationFits(decoderOf(seed, bytes), frameOf(sizeShift, channels, bytes));
                }
            }
        }
    }

    private void assertAllocationFits(final RangeDecoder decoder, final CeltFrame frame) {
        final var allocator = new CeltBitAllocator();

        final var allocation = allocator.allocate(decoder, frame);

        var total = 0;
        for (var band = frame.startBand(); band < frame.endBand(); band++) {
            assertThat(allocator.pulses(band)).isGreaterThanOrEqualTo(0);
            assertThat(allocator.fineBits(band)).isBetween(0, 8 + 1);
            total += allocator.pulses(band) + allocator.fineBits(band) * frame.channels() * 8;
        }
        assertThat(total).isLessThanOrEqualTo(frame.frameBits() * 8);
        assertThat(allocation.codedBands()).isBetween(frame.startBand() + 1, frame.endBand());
        assertThat(allocation.remainingBits()).isGreaterThanOrEqualTo(0);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void skippedBandsHaveNoPulses(final int channels) {
        final var frame = frameOf(3, channels, 30);
        final var allocator = new CeltBitAllocator();

        final var allocation = allocator.allocate(decoderOf(99, 30), frame);

        for (var band = allocation.codedBands(); band < frame.endBand(); band++) {
            assertThat(allocator.pulses(band)).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void givesNoFewerBitsToAFrameThatHasMoreOfThem(final int sizeShift) {
        final var bytes = 100;
        final var small = new CeltBitAllocator();
        final var large = new CeltBitAllocator();
        final var decoderForSmall = decoderOf(5, bytes);
        final var decoderForLarge = decoderOf(5, bytes);
        final var smallFrame = frameOf(sizeShift, 1, bytes);

        small.allocate(decoderForSmall, smallFrame);
        large.allocate(decoderForLarge, frameOf(sizeShift, 1, bytes * 2));

        var smallTotal = 0;
        var largeTotal = 0;
        for (var band = 0; band < 21; band++) {
            smallTotal += small.pulses(band);
            largeTotal += large.pulses(band);
        }
        assertThat(largeTotal).isGreaterThanOrEqualTo(smallTotal);
    }
}
