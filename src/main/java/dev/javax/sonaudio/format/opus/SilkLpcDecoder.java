package dev.javax.sonaudio.format.opus;

import java.util.Arrays;

/**
 * Decodes the linear prediction filter of a SILK frame (RFC 6716 section 4.2.7.5): its line spectral frequencies from a
 * codebook entry and per-frequency residuals, whose prediction from the next one is undone, the stabilised result, and
 * for a frame of four subframes the interpolation between them and the last frame's, which gives the first two
 * subframes a filter of their own. Then each set of frequencies becomes the filter's coefficients.
 */
final class SilkLpcDecoder {

    private static final int MODEL_BITS = 8;
    private static final int RESIDUAL_BIAS = 102;
    private static final int RESIDUAL_ESCAPE = 4;
    private static final int RESIDUAL_UNIT = 1024;
    private static final int NO_INTERPOLATION = 4;
    private static final int NLSF_MAX = 32767;
    private static final int CODEBOOK_STEP = 128;
    private static final int RESIDUAL_WEIGHT_UNIT = 16384;

    // The tables of the narrowband and mediumband filter, which has 10 coefficients, and of the wideband one, which has
    // 16, and the step the residuals are quantised with.
    private enum BandwidthTables {
        NARROWBAND_MEDIUMBAND(
                11796,
                SilkTables.LSF_S2_SEL_NBMB,
                SilkTables.LSF_PRED_WEIGHTS_NBMB,
                SilkTables.LSF_WEIGHT_SEL_NBMB,
                SilkTables.LSF_CODEBOOK_NBMB,
                SilkTables.LSF_WEIGHT_NBMB,
                SilkTables.LSF_MIN_SPACING_NBMB,
                SilkModels.LSF_S1[0]),
        WIDEBAND(
                9830,
                SilkTables.LSF_S2_SEL_WB,
                SilkTables.LSF_PRED_WEIGHTS_WB,
                SilkTables.LSF_WEIGHT_SEL_WB,
                SilkTables.LSF_CODEBOOK_WB,
                SilkTables.LSF_WEIGHT_WB,
                SilkTables.LSF_MIN_SPACING_WB,
                SilkModels.LSF_S1[1]);

        private final int quantizationStep;
        private final int[][] stage2Selection;
        private final int[][] predictionWeights;
        private final int[][] predictionSelection;
        private final int[][] codebook;
        private final int[][] weights;
        private final int[] minimumSpacing;
        private final int[][] stage1Models;

        BandwidthTables(
                final int quantizationStep,
                final int[][] stage2Selection,
                final int[][] predictionWeights,
                final int[][] predictionSelection,
                final int[][] codebook,
                final int[][] weights,
                final int[] minimumSpacing,
                final int[][] stage1Models) {
            this.quantizationStep = quantizationStep;
            this.stage2Selection = stage2Selection;
            this.predictionWeights = predictionWeights;
            this.predictionSelection = predictionSelection;
            this.codebook = codebook;
            this.weights = weights;
            this.minimumSpacing = minimumSpacing;
            this.stage1Models = stage1Models;
        }

        static BandwidthTables of(final OpusBandwidth bandwidth) {
            return switch (bandwidth) {
                case WIDEBAND -> WIDEBAND;
                case NARROWBAND, MEDIUMBAND -> NARROWBAND_MEDIUMBAND;
                case SUPERWIDEBAND, FULLBAND -> throw new IllegalArgumentException("SILK is at most wideband");
            };
        }
    }

    private final int[] residualIndices = new int[SilkLpcUtils.MAX_ORDER];
    private final int[] residuals = new int[SilkLpcUtils.MAX_ORDER];

    /**
     * Reads a frame's filter and makes it the channel's last one.
     *
     * @param rangeDecoder the range decoder, positioned at the filter's first symbol
     * @param layout the frame's shape
     * @param state the channel, whose line spectral frequencies and coefficients are replaced by this frame's
     * @param signalType the frame's signal type
     * @return the frame's filters
     */
    SilkLpc decode(
            final RangeDecoder rangeDecoder,
            final SilkLayout layout,
            final SilkChannelState state,
            final SilkSignalType signalType) {
        final var tables = BandwidthTables.of(layout.bandwidth());
        final var order = layout.order();
        final var stage1 = rangeDecoder.decodeIcdf(tables.stage1Models[signalType.voicedIndex()], MODEL_BITS);
        readResidualIndices(rangeDecoder, tables, order, stage1);
        undoPrediction(tables, order, stage1);
        final var nlsf = stabilisedNlsf(tables, order, stage1);
        return interpolate(rangeDecoder, layout, state, nlsf);
    }

    // The index of each frequency's residual, 4 and under for the usual ones, with an extension for the rest.
    private void readResidualIndices(
            final RangeDecoder rangeDecoder, final BandwidthTables tables, final int order, final int stage1) {
        for (var index = 0; index < order; index++) {
            final var model = SilkModels.LSF_S2[tables.stage2Selection[stage1][index]];
            var value = rangeDecoder.decodeIcdf(model, MODEL_BITS) - RESIDUAL_ESCAPE;
            if (value == -RESIDUAL_ESCAPE) {
                value -= rangeDecoder.decodeIcdf(SilkModels.LSF_S2_EXT, MODEL_BITS);
            } else if (value == RESIDUAL_ESCAPE) {
                value += rangeDecoder.decodeIcdf(SilkModels.LSF_S2_EXT, MODEL_BITS);
            }
            residualIndices[index] = value;
        }
    }

    // Dequantises the residuals, from the last frequency back, each added to the next one's weighted residual.
    private void undoPrediction(final BandwidthTables tables, final int order, final int stage1) {
        for (var index = order - 1; index >= 0; index--) {
            var residual = residualIndices[index] * RESIDUAL_UNIT;
            if (residualIndices[index] < 0) {
                residual += RESIDUAL_BIAS;
            } else if (residualIndices[index] > 0) {
                residual -= RESIDUAL_BIAS;
            }
            residual = (short) ((residual * tables.quantizationStep) >> 16);
            if (index + 1 < order) {
                final var selected = tables.predictionSelection[stage1][index];
                final var weight = tables.predictionWeights[selected][index];
                residual = (short) (residual + ((residuals[index + 1] * weight) >> 8));
            }
            residuals[index] = residual;
        }
    }

    // The codebook's frequencies moved by their residuals, divided by the weights, and then stabilised.
    private int[] stabilisedNlsf(final BandwidthTables tables, final int order, final int stage1) {
        final var nlsf = new int[SilkLpcUtils.MAX_ORDER];
        for (var index = 0; index < order; index++) {
            final var codebookValue = tables.codebook[stage1][index];
            final var weight = tables.weights[stage1][index];
            final var value = codebookValue * CODEBOOK_STEP + (residuals[index] * RESIDUAL_WEIGHT_UNIT) / weight;
            nlsf[index] = Math.clamp(value, 0, NLSF_MAX);
        }
        return SilkLpcUtils.stabilisedNlsf(nlsf, order, tables.minimumSpacing);
    }

    // A frame of four subframes may say how far to interpolate its first two subframes' frequencies from the last
    // frame's towards its own; one that doesn't, or that follows no frame, filters all subframes alike.
    private SilkLpc interpolate(
            final RangeDecoder rangeDecoder, final SilkLayout layout, final SilkChannelState state, final int[] nlsf) {
        final var order = layout.order();
        var factor = NO_INTERPOLATION;
        var leadIn = new float[SilkLpcUtils.MAX_ORDER];
        var leadInUse = SilkLpc.LeadIn.ABSENT;
        if (layout.subframes() == 4) {
            final var offset = rangeDecoder.decodeIcdf(SilkModels.LSF_INTERPOLATION_OFFSET, MODEL_BITS);
            if (offset != NO_INTERPOLATION && state.history() == SilkHistory.DECODED) {
                factor = offset;
                leadInUse = SilkLpc.LeadIn.PRESENT;
                leadIn = leadInFilter(state, nlsf, order, offset);
            }
        }
        final var body = SilkLpcUtils.nlsfToLpc(nlsf, order);
        System.arraycopy(nlsf, 0, state.nlsf(), 0, order);
        System.arraycopy(body, 0, state.lpc(), 0, order);
        return new SilkLpc(body, leadIn, leadInUse, factor);
    }

    // The filter of the frequencies a number of quarters of the way from the last frame's to this one's, which is the
    // last frame's own filter when that number is 0.
    private float[] leadInFilter(final SilkChannelState state, final int[] nlsf, final int order, final int offset) {
        if (offset == 0) {
            return Arrays.copyOf(state.lpc(), SilkLpcUtils.MAX_ORDER);
        }
        final var previous = state.nlsf();
        final var interpolated = new int[SilkLpcUtils.MAX_ORDER];
        for (var index = 0; index < order; index++) {
            interpolated[index] = (short) (previous[index] + (((nlsf[index] - previous[index]) * offset) >> 2));
        }
        return SilkLpcUtils.nlsfToLpc(interpolated, order);
    }
}
