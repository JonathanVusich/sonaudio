package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Decodes excitation that the test encodes with the RFC's range encoder, symbol by symbol, from the same models.
 */
class SilkExcitationTest {

    private static final int TABLE_BITS = 8;
    private static final int RATE_LEVEL = 3;
    private static final int BLOCK_SIZE = 16;
    private static final int NARROWBAND_10MS_BLOCKS = 5;
    private static final float Q23 = 8388608.0f;
    private static final SilkLayout LAYOUT = new SilkLayout(OpusBandwidth.NARROWBAND, 10);
    // The offset that unvoiced frames of the low quantisation offset add to every sample.
    private static final int UNVOICED_LOW_OFFSET = SilkTables.QUANT_OFFSET[0][0];

    private final SilkExcitation excitation = new SilkExcitation();
    private final RangeEncoder encoder = new RangeEncoder(256);

    @Test
    void decodesBlocksOfSixteenSamplesWithoutPulsesAsTheQuantisationOffset() {
        encodeHeader();
        encodePulseCounts(0, 0, 0, 0, 0);

        final var samples = decode();

        for (var index = 0; index < NARROWBAND_10MS_BLOCKS * BLOCK_SIZE; index++) {
            assertThat(magnitude(samples, index)).as("sample %d", index).isEqualTo(UNVOICED_LOW_OFFSET);
        }
    }

    @Test
    void decodesAPulseAtItsPositionInItsBlock() {
        encodeHeader();
        encodePulseCounts(0, 1, 0, 0, 0);
        encodeSinglePulseLocation(5);
        encoder.encodeIcdf(1, SilkModels.EXCITATION_SIGN[1][0][1], TABLE_BITS);

        final var samples = decode();

        for (var index = 0; index < NARROWBAND_10MS_BLOCKS * BLOCK_SIZE; index++) {
            final var expected = magnitudeWithPulseAt(index, BLOCK_SIZE + 5);
            assertThat(magnitude(samples, index)).as("sample %d", index).isEqualTo(expected);
        }
    }

    // A negative pulse is offset towards zero by 20 after its Q8 value is OR'd with the quantisation offset, which
    // for a negative number is not the same as adding it.
    @Test
    void decodesANegativePulseWithItsOwnOffset() {
        encodeHeader();
        encodePulseCounts(1, 0, 0, 0, 0);
        encodeSinglePulseLocation(0);
        encoder.encodeIcdf(0, SilkModels.EXCITATION_SIGN[1][0][1], TABLE_BITS);

        final var samples = decode();

        assertThat(magnitude(samples, 0)).isEqualTo(-(-256 | UNVOICED_LOW_OFFSET) - 20);
        assertThat(magnitude(samples, 1)).isEqualTo(UNVOICED_LOW_OFFSET);
    }

    // A count of 17 says there are low bits, and is followed by a count of the pulses, which here is none: the low
    // bits alone make a pulse of 1 where the bit is set.
    @Test
    void decodesLowBitsAfterAnEscapedPulseCount() {
        encodeHeader();
        encoder.encodeIcdf(17, SilkModels.PULSE_COUNT[RATE_LEVEL], TABLE_BITS);
        encoder.encodeIcdf(0, SilkModels.PULSE_COUNT[9], TABLE_BITS);
        for (var block = 1; block < NARROWBAND_10MS_BLOCKS; block++) {
            encoder.encodeIcdf(0, SilkModels.PULSE_COUNT[RATE_LEVEL], TABLE_BITS);
        }
        for (var index = 0; index < BLOCK_SIZE; index++) {
            encoder.encodeIcdf(lowBit(index), SilkModels.EXCITATION_LSB, TABLE_BITS);
        }
        encoder.encodeIcdf(1, SilkModels.EXCITATION_SIGN[1][0][0], TABLE_BITS);

        final var samples = decode();

        for (var index = 0; index < NARROWBAND_10MS_BLOCKS * BLOCK_SIZE; index++) {
            assertThat(magnitude(samples, index)).as("sample %d", index).isEqualTo(magnitudeWithPulseAt(index, 3));
        }
    }

    // Only the fourth sample of the block has its low bit set.
    private int lowBit(final int index) {
        if (index == 3) {
            return 1;
        }
        return 0;
    }

    private int magnitudeWithPulseAt(final int index, final int pulseIndex) {
        if (index == pulseIndex) {
            return 256 + UNVOICED_LOW_OFFSET - 20;
        }
        return UNVOICED_LOW_OFFSET;
    }

    // The seed, which only picks which samples have their sign flipped, and the rate level that picks the models of
    // the pulse counts.
    private void encodeHeader() {
        encoder.encodeIcdf(2, SilkModels.LCG_SEED, TABLE_BITS);
        encoder.encodeIcdf(RATE_LEVEL, SilkModels.EXC_RATE[0], TABLE_BITS);
    }

    private void encodePulseCounts(final int... counts) {
        for (final var count : counts) {
            encoder.encodeIcdf(count, SilkModels.PULSE_COUNT[RATE_LEVEL], TABLE_BITS);
        }
    }

    // One pulse in a block of 16 is located by which half it falls in, then which half of that, down to a pair.
    private void encodeSinglePulseLocation(final int position) {
        var start = 0;
        var width = BLOCK_SIZE;
        for (var level = 0; level < 4; level++) {
            final var half = width / 2;
            if (position < start + half) {
                encoder.encodeIcdf(1, SilkModels.PULSE_LOCATION[level][0], TABLE_BITS);
            } else {
                encoder.encodeIcdf(0, SilkModels.PULSE_LOCATION[level][0], TABLE_BITS);
                start += half;
            }
            width = half;
        }
    }

    private float[] decode() {
        final var bytes = encoder.finish();
        final var rangeDecoder = new RangeDecoder();
        rangeDecoder.initialise(bytes, 0, bytes.length);
        excitation.decode(rangeDecoder, LAYOUT, SilkSignalType.UNVOICED, QuantizationOffset.LOW);
        return excitation.samples();
    }

    private int magnitude(final float[] samples, final int index) {
        return Math.round(Math.abs(samples[index]) * Q23);
    }
}
