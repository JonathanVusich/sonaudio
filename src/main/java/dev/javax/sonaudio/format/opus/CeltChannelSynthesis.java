package dev.javax.sonaudio.format.opus;

import java.util.Arrays;

/**
 * Turns the coefficients of one channel of a CELT frame into samples (RFC 6716 section 4.3.7): the inverse MDCT of each
 * block, overlapped with the block before it by the low-overlap window, the pitch post-filter, which cross-fades from
 * the last frame's parameters to this frame's, and the de-emphasis. It keeps what the next frame needs of this one:
 * the overlap, the history the post-filter looks back into, the post-filter's parameters and the de-emphasis
 * filter's memory.
 */
final class CeltChannelSynthesis {

    private static final int OVERLAP = CeltWindowTables.LENGTH;
    private static final int HALF_OVERLAP = OVERLAP / 2;
    // The samples before the frame's start that the buffer keeps, which the post-filter looks back into.
    private static final int HISTORY = 1024;
    private static final float DEEMPHASIS = (float) 0.8500061035;
    private static final float MIN_GAIN = Math.ulp(1.0f);

    private final CeltMdct[] transforms = {new CeltMdct(120), new CeltMdct(240), new CeltMdct(480), new CeltMdct(960)};
    private final float[] samples = new float[2048];
    private final float[] gains = new float[3];
    private final float[] previousGains = new float[3];
    private final float[] newGains = new float[3];
    private int period;
    private int previousPeriod;
    private int newPeriod;
    private float emphasis;

    /**
     * Forgets the last frames.
     */
    void reset() {
        Arrays.fill(samples, 0f);
        Arrays.fill(gains, 0f);
        Arrays.fill(previousGains, 0f);
        Arrays.fill(newGains, 0f);
        emphasis = 0f;
    }

    /**
     * Synthesises a frame.
     *
     * @param frame the frame
     * @param coefficients the channel's denormalised coefficients, grouped by block
     * @param downmix how the coefficients' channel is mixed into the output's
     * @param postfilter the frame's post-filter
     * @param output where the samples go, which are {@link CeltFrame#frameSamples} long
     */
    void synthesize(
            final CeltFrame frame,
            final float[] coefficients,
            final CeltDownmix downmix,
            final CeltPostfilterParameters postfilter,
            final float[] output) {
        transformBlocks(frame, coefficients);
        if (downmix == CeltDownmix.AVERAGE) {
            for (var index = 0; index < frame.frameSamples(); index++) {
                samples[HISTORY + index] *= 0.5f;
            }
        }
        newPeriod = postfilter.period();
        newGains[0] = postfilter.centreGain();
        newGains[1] = postfilter.nearGain();
        newGains[2] = postfilter.farGain();
        applyPostfilter(frame.frameSamples());
        applyDeemphasis(frame.frameSamples(), output);
    }

    private void transformBlocks(final CeltFrame frame, final float[] coefficients) {
        final var blocks = frame.blocks();
        final var blockSize = frame.frameSamples() / blocks;
        var transform = transforms[frame.sizeShift()];
        if (frame.blockMode() == CeltBlockMode.SHORT) {
            transform = transforms[0];
        }
        for (var block = 0; block < blocks; block++) {
            final var start = HISTORY + block * blockSize;
            transform.inverse(coefficients, block, blocks, samples, start + HALF_OVERLAP);
            overlapWithWindow(start);
        }
    }

    // Cross-fades the end of the block before, which sits at the start of this block, into the start of this one.
    private void overlapWithWindow(final int start) {
        final var window = CeltWindowTables.WINDOW;
        for (var index = 0; index < HALF_OVERLAP; index++) {
            final var mirror = OVERLAP - 1 - index;
            final var before = samples[start + index];
            final var after = samples[start + mirror];
            samples[start + index] = before * window[mirror] - after * window[index];
            samples[start + mirror] = before * window[index] + after * window[mirror];
        }
    }

    private void applyPostfilter(final int length) {
        final var filterLength = length - 2 * OVERLAP;
        crossFadePostfilter(HISTORY);
        shiftPostfilterParameters();
        if (length > OVERLAP) {
            crossFadePostfilter(HISTORY + OVERLAP);
            if (gains[0] > MIN_GAIN && filterLength > 0) {
                filterSamples(HISTORY + 2 * OVERLAP, filterLength);
            }
            previousPeriod = period;
            System.arraycopy(gains, 0, previousGains, 0, 3);
        }
        System.arraycopy(samples, length, samples, 0, HISTORY + HALF_OVERLAP);
    }

    private void shiftPostfilterParameters() {
        previousPeriod = period;
        System.arraycopy(gains, 0, previousGains, 0, 3);
        period = newPeriod;
        System.arraycopy(newGains, 0, gains, 0, 3);
    }

    // Applies the post-filter over the overlap, from the previous frame's parameters to this frame's. FFmpeg mixes
    // doubles and floats here, and so does this.
    private void crossFadePostfilter(final int start) {
        if (gains[0] == 0.0 && previousGains[0] == 0.0) {
            return;
        }
        final var oldPeriod = previousPeriod;
        final var newerPeriod = period;
        var x1 = samples[start - newerPeriod + 1];
        var x2 = samples[start - newerPeriod];
        var x3 = samples[start - newerPeriod - 1];
        var x4 = samples[start - newerPeriod - 2];
        for (var index = 0; index < OVERLAP; index++) {
            final var weight = CeltWindowTables.WINDOW_SQUARED[index];
            final var x0 = samples[start + index - newerPeriod + 2];
            samples[start + index] = (float)
                    (samples[start + index] + crossFadeSum(start + index, oldPeriod, weight, x0, x1, x2, x3, x4));
            x4 = x3;
            x3 = x2;
            x2 = x1;
            x1 = x0;
        }
    }

    private double crossFadeSum(
            final int position,
            final int oldPeriod,
            final float weight,
            final float x0,
            final float x1,
            final float x2,
            final float x3,
            final float x4) {
        final var old = (1.0 - weight) * previousGains[0] * samples[position - oldPeriod]
                + (1.0 - weight)
                        * previousGains[1]
                        * (samples[position - oldPeriod - 1] + samples[position - oldPeriod + 1])
                + (1.0 - weight)
                        * previousGains[2]
                        * (samples[position - oldPeriod - 2] + samples[position - oldPeriod + 2]);
        return old + weight * gains[0] * x2 + weight * gains[1] * (x1 + x3) + weight * gains[2] * (x0 + x4);
    }

    private void filterSamples(final int start, final int length) {
        final var centre = gains[0];
        final var nearer = gains[1];
        final var farther = gains[2];
        var x4 = samples[start - period - 2];
        var x3 = samples[start - period - 1];
        var x2 = samples[start - period];
        var x1 = samples[start - period + 1];
        for (var index = 0; index < length; index++) {
            final var x0 = samples[start + index - period + 2];
            samples[start + index] += centre * x2 + nearer * (x1 + x3) + farther * (x0 + x4);
            x4 = x3;
            x3 = x2;
            x2 = x1;
            x1 = x0;
        }
    }

    private void applyDeemphasis(final int length, final float[] output) {
        var memory = emphasis;
        final var first = HISTORY - length;
        for (var index = 0; index < length; index++) {
            memory = samples[first + index] + memory * DEEMPHASIS;
            output[index] = memory;
        }
        emphasis = memory;
        if (Math.abs(emphasis) < Float.MIN_NORMAL || !Float.isFinite(emphasis)) {
            emphasis = 0f;
        }
    }
}
