package dev.javax.sonaudio.format.opus;

/**
 * Turns a SILK frame's excitation into samples (RFC 6716 section 4.2.7.9): for a voiced frame the long-term prediction
 * filter first, which adds the excitation of a pitch period ago to it, and then the short-term prediction filter. As in
 * FFmpeg's decoder both run in floats, on the unscaled excitation times each subframe's gain, and the output is clipped
 * to -1 to 1.
 *
 * <p>When a subframe's filter differs from the one the history was made with, the history is first run back through
 * the new filter, which gives the residual that the pitch lag looks into.
 */
final class SilkSynthesis {

    private static final int LTP_ORDER = 5;
    private static final int LTP_RADIUS = LTP_ORDER / 2;
    private static final int MAX_PITCH_LAG = 288;
    // How far back the residual is kept: the longest pitch lag and the long-term filter's reach on either side of it.
    private static final int RESIDUAL_HISTORY = MAX_PITCH_LAG + LTP_RADIUS;
    private static final int UNINTERPOLATED = 4;
    private static final float UNIT_SCALE = 1.0f;

    private final float[] residual = new float[RESIDUAL_HISTORY + SilkChannelState.HISTORY];

    /**
     * Synthesises the samples of a frame into the channel's output, and moves its history on.
     *
     * @param state the channel's state, whose output and filter state are written
     * @param layout the frame's shape
     * @param sideInfo the frame's parameters
     * @param excitation the frame's excitation, at least {@link SilkLayout#frameLength} samples
     */
    void synthesise(
            final SilkChannelState state,
            final SilkLayout layout,
            final SilkSideInfo sideInfo,
            final float[] excitation) {
        System.arraycopy(excitation, 0, residual, RESIDUAL_HISTORY, layout.frameLength());
        for (var subframe = 0; subframe < layout.subframes(); subframe++) {
            if (sideInfo.signalType() == SilkSignalType.VOICED) {
                predictFromPitch(state, layout, sideInfo, subframe);
            }
            synthesiseSubframe(state, layout, sideInfo, subframe);
        }
        state.finishFrame(layout.frameLength(), sideInfo.signalType());
    }

    // The long-term prediction of a voiced subframe: the residual of the history under the subframe's own filter, then
    // the excitation plus the taps' weighting of the residual one pitch lag back.
    private void predictFromPitch(
            final SilkChannelState state, final SilkLayout layout, final SilkSideInfo sideInfo, final int subframe) {
        final var length = layout.subframeLength();
        final var start = RESIDUAL_HISTORY + subframe * length;
        final var lpc = sideInfo.lpc();
        var rewhitenEnd = -subframe * length;
        var scale = sideInfo.ltpScale();
        if (subframe >= 2 && lpc.interpolationFactor() != UNINTERPOLATED) {
            rewhitenEnd = -(subframe - 2) * length;
            scale = UNIT_SCALE;
        }
        final var gain = sideInfo.gain(subframe);
        final var lag = sideInfo.pitchLag(subframe);
        rewhiten(state, layout, sideInfo, subframe, -lag - LTP_RADIUS, rewhitenEnd, scale);
        if (rewhitenEnd != 0) {
            final var rescale = sideInfo.gain(subframe - 1) / gain;
            for (var offset = rewhitenEnd; offset < 0; offset++) {
                residual[start + offset] *= rescale;
            }
        }
        for (var offset = 0; offset < length; offset++) {
            residual[start + offset] = longTermPrediction(sideInfo, subframe, start + offset);
        }
    }

    // The residual from offset "from" up to "to" before the subframe's start: the output minus the filter's
    // prediction, clipped, scaled and divided by the subframe's gain.
    private void rewhiten(
            final SilkChannelState state,
            final SilkLayout layout,
            final SilkSideInfo sideInfo,
            final int subframe,
            final int from,
            final int to,
            final float scale) {
        final var output = state.output();
        final var lpc = sideInfo.lpc();
        final var order = layout.order();
        final var outputStart = SilkChannelState.HISTORY + subframe * layout.subframeLength();
        final var residualStart = RESIDUAL_HISTORY + subframe * layout.subframeLength();
        final var gain = sideInfo.gain(subframe);
        for (var offset = from; offset < to; offset++) {
            var sum = output[outputStart + offset];
            for (var tap = 0; tap < order; tap++) {
                sum -= lpc.coefficient(subframe, tap) * output[outputStart + offset - tap - 1];
            }
            residual[residualStart + offset] = clip(sum) * scale / gain;
        }
    }

    private float longTermPrediction(final SilkSideInfo sideInfo, final int subframe, final int position) {
        var sum = residual[position];
        final var lagged = position - sideInfo.pitchLag(subframe) + LTP_RADIUS;
        for (var tap = 0; tap < LTP_ORDER; tap++) {
            sum += sideInfo.ltpTap(subframe, tap) * residual[lagged - tap];
        }
        return sum;
    }

    private void synthesiseSubframe(
            final SilkChannelState state, final SilkLayout layout, final SilkSideInfo sideInfo, final int subframe) {
        final var output = state.output();
        final var filterState = state.lpcHistory();
        final var lpc = sideInfo.lpc();
        final var order = layout.order();
        final var start = SilkChannelState.HISTORY + subframe * layout.subframeLength();
        final var residualStart = RESIDUAL_HISTORY + subframe * layout.subframeLength();
        final var gain = sideInfo.gain(subframe);
        for (var offset = 0; offset < layout.subframeLength(); offset++) {
            var sum = residual[residualStart + offset] * gain;
            for (var tap = 1; tap <= order; tap++) {
                sum += lpc.coefficient(subframe, tap - 1) * filterState[start + offset - tap];
            }
            filterState[start + offset] = sum;
            output[start + offset] = clip(sum);
        }
    }

    private static float clip(final float sample) {
        return Math.min(Math.max(sample, -1.0f), 1.0f);
    }
}
