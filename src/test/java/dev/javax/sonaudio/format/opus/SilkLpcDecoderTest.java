package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Decodes filters that the test encodes with the RFC's range encoder, with every residual index at its middle value, so
 * that each frequency is its codebook entry's, which the filter conversion's own tests cover the arithmetic of.
 */
class SilkLpcDecoderTest {

    private static final int TABLE_BITS = 8;
    private static final int STAGE_ONE_INDEX = 5;
    private static final int NEXT_STAGE_ONE_INDEX = 9;
    private static final int ORDER = 10;
    // The residual index 0 is the table's symbol 4: the indices are coded from -4, and above that by an extension.
    private static final int MIDDLE_SYMBOL = 4;
    private static final SilkLayout TWO_SUBFRAMES = new SilkLayout(OpusBandwidth.NARROWBAND, 10);
    private static final SilkLayout FOUR_SUBFRAMES = new SilkLayout(OpusBandwidth.NARROWBAND, 20);

    private final SilkLpcConverter converter = new SilkLpcConverter();
    private final SilkLpcDecoder decoder = new SilkLpcDecoder();
    private final SilkChannelState state = new SilkChannelState();

    @Test
    void takesTheCodebookEntryAsTheFrequenciesWhenEveryResidualIsZero() {
        final var rangeDecoder = encodeFilter(STAGE_ONE_INDEX);

        final var lpc = decoder.decode(rangeDecoder, TWO_SUBFRAMES, state, SilkSignalType.UNVOICED);

        final var expectedNlsf = codebookFrequencies(STAGE_ONE_INDEX);
        assertThat(Arrays.copyOf(state.nlsf(), ORDER)).isEqualTo(Arrays.copyOf(expectedNlsf, ORDER));
        final var expected = converter.nlsfToLpc(expectedNlsf, ORDER);
        for (var tap = 0; tap < ORDER; tap++) {
            assertThat(lpc.coefficient(0, tap)).isEqualTo(expected[tap]);
            assertThat(lpc.coefficient(1, tap)).isEqualTo(expected[tap]);
            assertThat(state.lpc()[tap]).isEqualTo(expected[tap]);
        }
        assertThat(lpc.interpolationFactor()).isEqualTo(4);
    }

    @Test
    void doesNotInterpolateTheFirstFrameBecauseThereIsNoFrameBeforeIt() {
        final var rangeDecoder = encodeFilterWithInterpolation(STAGE_ONE_INDEX, 2);

        final var lpc = decoder.decode(rangeDecoder, FOUR_SUBFRAMES, state, SilkSignalType.UNVOICED);

        assertThat(lpc.interpolationFactor()).isEqualTo(4);
        assertThat(lpc.coefficient(0, 0)).isEqualTo(lpc.coefficient(3, 0));
    }

    @Test
    void interpolatesTheFirstTwoSubframesFromTheLastFramesFrequencies() {
        decoder.decode(
                encodeFilterWithInterpolation(STAGE_ONE_INDEX, 4), FOUR_SUBFRAMES, state, SilkSignalType.UNVOICED);
        state.finishFrame(FOUR_SUBFRAMES.frameLength(), SilkSignalType.UNVOICED);
        final var previous = codebookFrequencies(STAGE_ONE_INDEX);
        final var current = codebookFrequencies(NEXT_STAGE_ONE_INDEX);
        final var halfway = new int[SilkLpcUtils.MAX_ORDER];
        for (var index = 0; index < ORDER; index++) {
            halfway[index] = previous[index] + (((current[index] - previous[index]) * 2) >> 2);
        }

        final var lpc = decoder.decode(
                encodeFilterWithInterpolation(NEXT_STAGE_ONE_INDEX, 2), FOUR_SUBFRAMES, state, SilkSignalType.UNVOICED);

        final var leadIn = converter.nlsfToLpc(halfway, ORDER);
        final var body = converter.nlsfToLpc(current, ORDER);
        assertThat(lpc.interpolationFactor()).isEqualTo(2);
        for (var tap = 0; tap < ORDER; tap++) {
            assertThat(lpc.coefficient(0, tap)).isEqualTo(leadIn[tap]);
            assertThat(lpc.coefficient(1, tap)).isEqualTo(leadIn[tap]);
            assertThat(lpc.coefficient(2, tap)).isEqualTo(body[tap]);
            assertThat(lpc.coefficient(3, tap)).isEqualTo(body[tap]);
        }
    }

    @Test
    void usesTheLastFramesOwnFilterWhenInterpolatingNoneOfTheWayToTheNewFrequencies() {
        decoder.decode(
                encodeFilterWithInterpolation(STAGE_ONE_INDEX, 4), FOUR_SUBFRAMES, state, SilkSignalType.UNVOICED);
        state.finishFrame(FOUR_SUBFRAMES.frameLength(), SilkSignalType.UNVOICED);
        final var previous = converter.nlsfToLpc(codebookFrequencies(STAGE_ONE_INDEX), ORDER);

        final var lpc = decoder.decode(
                encodeFilterWithInterpolation(NEXT_STAGE_ONE_INDEX, 0), FOUR_SUBFRAMES, state, SilkSignalType.UNVOICED);

        assertThat(lpc.interpolationFactor()).isZero();
        for (var tap = 0; tap < ORDER; tap++) {
            assertThat(lpc.coefficient(0, tap)).isEqualTo(previous[tap]);
        }
    }

    // The stabilised frequencies of a codebook entry with nothing added to them.
    private int[] codebookFrequencies(final int stageOneIndex) {
        final var frequencies = new int[SilkLpcUtils.MAX_ORDER];
        for (var index = 0; index < ORDER; index++) {
            frequencies[index] = SilkTables.LSF_CODEBOOK_NBMB[stageOneIndex][index] * 128;
        }
        return converter
                .stabilisedNlsf(frequencies, ORDER, SilkTables.LSF_MIN_SPACING_NBMB)
                .clone();
    }

    private RangeDecoder encodeFilter(final int stageOneIndex) {
        final var encoder = new RangeEncoder(256);
        encodeFrequencies(encoder, stageOneIndex);
        return decoderOf(encoder.finish());
    }

    private RangeDecoder encodeFilterWithInterpolation(final int stageOneIndex, final int interpolation) {
        final var encoder = new RangeEncoder(256);
        encodeFrequencies(encoder, stageOneIndex);
        encoder.encodeIcdf(interpolation, SilkModels.LSF_INTERPOLATION_OFFSET, TABLE_BITS);
        return decoderOf(encoder.finish());
    }

    private void encodeFrequencies(final RangeEncoder encoder, final int stageOneIndex) {
        encoder.encodeIcdf(stageOneIndex, SilkModels.LSF_S1[0][0], TABLE_BITS);
        for (var index = 0; index < ORDER; index++) {
            final var model = SilkModels.LSF_S2[SilkTables.LSF_S2_SEL_NBMB[stageOneIndex][index]];
            encoder.encodeIcdf(MIDDLE_SYMBOL, model, TABLE_BITS);
        }
    }

    private RangeDecoder decoderOf(final byte[] bytes) {
        final var rangeDecoder = new RangeDecoder();
        rangeDecoder.initialise(bytes, 0, bytes.length);
        return rangeDecoder;
    }
}
