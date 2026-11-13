package dev.javax.sonaudio.format.opus;

/**
 * Turns the mid and side channels of a stereo SILK frame into left and right (RFC 6716 section 4.2.8), predicting the
 * side channel from the mid with the frame's two weights. For the first samples of a frame the weights move linearly
 * from the previous frame's to this frame's, so a change of weights doesn't click. The mid channel is lowpass
 * filtered, and, as in FFmpeg's decoder, both channels are used one sample late, which is how far the unmixed output
 * lags the mid and side samples.
 */
final class SilkStereoMixer {

    private float previousPredictionWeight;
    private float previousMidWeight;
    private float predictionWeight;
    private float midWeight;

    /**
     * Sets the weights the next frame is unmixed with.
     *
     * @param predictionWeight how much of the lowpass filtered mid channel is added to the side channel
     * @param midWeight how much of the mid channel is added to the side channel's unfiltered sample
     */
    void setWeights(final float predictionWeight, final float midWeight) {
        this.predictionWeight = predictionWeight;
        this.midWeight = midWeight;
    }

    /**
     * Forgets the previous frame's weights, so that the next frame's weights move from zero.
     */
    void reset() {
        previousPredictionWeight = 0;
        previousMidWeight = 0;
    }

    /**
     * Unmixes the frame that the two channels have just decoded, with the weights last set, and makes those the
     * previous weights for the next frame.
     *
     * @param mid the mid channel, whose output ends with the frame
     * @param side the side channel, whose output ends with the frame
     * @param left where to write the left channel
     * @param right where to write the right channel
     * @param outputStart the index in {@code left} and {@code right} to start at
     * @param layout the frame's shape, whose bandwidth says how many samples the weights move over
     */
    void unmix(
            final SilkChannelState mid,
            final SilkChannelState side,
            final float[] left,
            final float[] right,
            final int outputStart,
            final SilkLayout layout) {
        final var frameLength = layout.frameLength();
        final var interpolationLength =
                SilkTables.STEREO_INTERP_LEN[layout.bandwidth().ordinal()];
        final var midStart = SilkChannelState.HISTORY - frameLength;
        for (var index = 0; index < frameLength; index++) {
            final var prediction = weightAt(previousPredictionWeight, predictionWeight, index, interpolationLength);
            final var midGain = weightAt(previousMidWeight, midWeight, index, interpolationLength);
            final var filtered = lowpassedMid(mid.output(), midStart + index);
            final var midSample = mid.output()[midStart + index - 1];
            final var sideSample = side.output()[midStart + index - 1];
            left[outputStart + index] = clip((1 + midGain) * midSample + sideSample + prediction * filtered);
            right[outputStart + index] = clip((1 - midGain) * midSample - sideSample - prediction * filtered);
        }
        previousPredictionWeight = predictionWeight;
        previousMidWeight = midWeight;
    }

    // The weight at a sample of the frame: it moves in a straight line from the previous frame's over the first
    // samples, and is the new weight from then on.
    private float weightAt(final float previous, final float current, final int index, final int length) {
        if (index >= length) {
            return current;
        }
        return previous + index * (current - previous) / length;
    }

    // A quarter, a half and a quarter of the three mid samples that end at the position, which is as the RFC's
    // low pass on the mid channel, computed in floats.
    private float lowpassedMid(final float[] mid, final int position) {
        return (float) (0.25 * (mid[position - 2] + 2 * mid[position - 1] + mid[position]));
    }

    private float clip(final float sample) {
        return Math.min(Math.max(sample, -1.0f), 1.0f);
    }
}
