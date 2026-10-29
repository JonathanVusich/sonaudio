package dev.javax.sonaudio.format.mp3;

/**
 * The inverse MDCT of Layer III's hybrid filterbank, for one channel (ISO/IEC 11172-3 section 2.4.3.4.10): each
 * subband's 18 lines become 36 windowed samples, whose first half is added to the second half of the subband's last
 * block. A long block is one 36-point transform, and a short block three 12-point ones that overlap within it. It
 * works as FFmpeg's floating-point decoder does on x86 processors with AVX, as AcoustID's fpcalc decodes with it:
 * long blocks go four subbands at a time, and short blocks one at a time, in the same order of operations, so the
 * samples come out the same floats. The decoder keeps one per channel, as each granule overlaps the one before.
 */
final class Imdct implements SubbandSamples {

    private static final int SUBBANDS = Spectrum.SUBBANDS;
    private static final int LINES = Spectrum.SUBBAND_LINES;
    private static final int GROUP = 4;
    private static final int WINDOW_SIZE = 40;
    private static final int WINDOW_HALF = WINDOW_SIZE / 2;
    private static final int SHORT_BLOCK_TYPE = 2;
    // A mixed block's two lowest subbands are long blocks with the normal window, whatever its block type.
    private static final int MIXED_LONG_SUBBANDS = 2;
    // Lines are checked for the last that isn't 0 in sixes, from the third subband down.
    private static final int ZERO_CHECK = 6;
    private static final double SCALAR = 1.759;
    private static final int SHORT_HALF = LINES / Windowing.WINDOWS;

    // The windows of the four block types, then the same with their odd coefficients negated, for odd subbands.
    private static final float[][] WINDOWS = windows();
    // The same for a group of four subbands, with their coefficients interleaved: whole, or with a mixed block's first
    // two subbands on the normal window.
    private static final float[][][] GROUP_WINDOWS = groupWindows();

    // cos(pi * i / 18) / 2, and 0.5 / cos(pi * (2 * i + 1) / 36) / 2 or / 4, for the 12-point transform.
    private static final float C3 = (float) (0.86602540378443864676 / 2);
    private static final float C4 = (float) (0.70710678118654752439 / 2);
    private static final float C5 = (float) (0.51763809020504152469 / 2);
    private static final float C6 = (float) (1.93185165257813657349 / 4);

    // The previous blocks' second halves, with four subbands' entries next to each other.
    private final float[] overlap = new float[SUBBANDS * LINES];
    // The granule's subband samples: sample t of subband s at t * 32 + s, as the synthesis filterbank takes each
    // instant's subbands together.
    private final float[] subbandSamples = new float[SUBBANDS * LINES];
    private final float[] lines = new float[SUBBANDS * LINES];
    private final float[] shortBlock = new float[2 * SHORT_HALF];
    private final FourImdct36 fourTransform = new FourImdct36();
    private final SingleImdct36 singleTransform = new SingleImdct36();

    /**
     * Transforms a granule's spectrum, which must have been reordered and had its aliasing reduced.
     */
    void transform(final Spectrum spectrum, final Windowing windowing) {
        for (var line = 0; line < lines.length; line++) {
            lines[line] = spectrum.line(line);
        }
        final var sublimit = lastNonZeroSubband();
        final var blockType = windowing.blockType().ordinal();
        final var lowSubbands = windowing.lowSubbands();
        final var longEnd = longBlocksEnd(sublimit, blockType, lowSubbands);
        transformLongBlocks(longEnd, lowSubbands, blockType);
        transformShortBlocks(longEnd, sublimit);
        copyZeroBands(sublimit);
    }

    /**
     * @param instant from 0 to 17
     * @return the subband's sample at the instant, from the granule last transformed
     */
    @Override
    public float subbandSample(final int instant, final int subband) {
        return subbandSamples[instant * SUBBANDS + subband];
    }

    // The subband after the last with a line that isn't 0, checked as FFmpeg checks, in sixes of the lines' bits, so a
    // negative 0 counts.
    private int lastNonZeroSubband() {
        var end = lines.length;
        while (end >= 2 * LINES) {
            end -= ZERO_CHECK;
            if (anyBitsSet(end)) {
                break;
            }
        }
        return end / LINES + 1;
    }

    private boolean anyBitsSet(final int start) {
        for (var line = start; line < start + ZERO_CHECK; line++) {
            if (Float.floatToRawIntBits(lines[line]) != 0) {
                return true;
            }
        }
        return false;
    }

    private static int longBlocksEnd(final int sublimit, final int blockType, final LowSubbands lowSubbands) {
        if (blockType != SHORT_BLOCK_TYPE) {
            return sublimit;
        }
        if (lowSubbands == LowSubbands.NORMAL_WINDOW) {
            return MIXED_LONG_SUBBANDS;
        }
        return 0;
    }

    private void transformLongBlocks(final int count, final LowSubbands lowSubbands, final int blockType) {
        final var mixed = lowSubbands == LowSubbands.NORMAL_WINDOW;
        final var groupsEnd = count - (count & (GROUP - 1));
        var subband = 0;
        for (; subband < groupsEnd; subband += GROUP) {
            final var firstGroup = mixed && subband < GROUP;
            final var window = GROUP_WINDOWS[firstGroup ? 1 : 0][blockType];
            fourTransform.transform(
                    subbandSamples, subband, overlap, bufferStart(subband), lines, LINES * subband, window);
        }
        for (; subband < count; subband++) {
            final var windowType = mixed && subband < MIXED_LONG_SUBBANDS ? 0 : blockType;
            final var window = WINDOWS[windowType + (GROUP & -(subband & 1))];
            singleTransform.transform(
                    subbandSamples, subband, overlap, bufferStart(subband), lines, LINES * subband, window);
        }
    }

    // Where a subband's entries begin in the overlap: four subbands' next to each other, 18 entries of four.
    private static int bufferStart(final int subband) {
        return GROUP * LINES * (subband / GROUP) + (subband & (GROUP - 1));
    }

    private void transformShortBlocks(final int first, final int sublimit) {
        for (var subband = first; subband < sublimit; subband++) {
            final var window = WINDOWS[SHORT_BLOCK_TYPE + (GROUP & -(subband & 1))];
            final var start = bufferStart(subband);
            final var lineStart = LINES * subband;
            for (var instant = 0; instant < SHORT_HALF; instant++) {
                subbandSamples[instant * SUBBANDS + subband] = overlap[start + GROUP * instant];
            }
            imdct12(lineStart);
            for (var sample = 0; sample < SHORT_HALF; sample++) {
                final var instant = SHORT_HALF + sample;
                subbandSamples[instant * SUBBANDS + subband] =
                        shortBlock[sample] * window[sample] + overlap[start + GROUP * (SHORT_HALF + sample)];
                overlap[start + GROUP * (2 * SHORT_HALF + sample)] =
                        shortBlock[sample + SHORT_HALF] * window[sample + SHORT_HALF];
            }
            imdct12(lineStart + 1);
            for (var sample = 0; sample < SHORT_HALF; sample++) {
                final var instant = 2 * SHORT_HALF + sample;
                subbandSamples[instant * SUBBANDS + subband] =
                        shortBlock[sample] * window[sample] + overlap[start + GROUP * (2 * SHORT_HALF + sample)];
                overlap[start + GROUP * sample] = shortBlock[sample + SHORT_HALF] * window[sample + SHORT_HALF];
            }
            imdct12(lineStart + 2);
            for (var sample = 0; sample < SHORT_HALF; sample++) {
                overlap[start + GROUP * sample] = shortBlock[sample] * window[sample] + overlap[start + GROUP * sample];
                overlap[start + GROUP * (SHORT_HALF + sample)] =
                        shortBlock[sample + SHORT_HALF] * window[sample + SHORT_HALF];
                overlap[start + GROUP * (2 * SHORT_HALF + sample)] = 0;
            }
        }
    }
    // FFmpeg's 12-point IMDCT of every third line from the start, "by hand", into shortBlock.
    private void imdct12(final int start) {
        final var in0 = lines[start];
        var in1 = lines[start + 3] + lines[start];
        var in2 = lines[start + 6] + lines[start + 3];
        var in3 = lines[start + 9] + lines[start + 6];
        final var in4 = lines[start + 12] + lines[start + 9];
        var in5 = lines[start + 15] + lines[start + 12];
        in5 += in3;
        in3 += in1;
        in2 = 2 * C3 * in2;
        in3 = 4 * C3 * in3;
        final var t1 = in0 - in4;
        final var t2 = 2 * C4 * (in1 - in5);
        shortBlock[7] = t1 + t2;
        shortBlock[10] = t1 + t2;
        shortBlock[1] = t1 - t2;
        shortBlock[4] = t1 - t2;
        var base = in0 + in4 * (1.0f / (1 << 1));
        final var upper = base + in2;
        in5 += 2 * in1;
        final var middle = 1 * C5 * (in5 + in3);
        shortBlock[8] = upper + middle;
        shortBlock[9] = upper + middle;
        shortBlock[2] = upper - middle;
        shortBlock[3] = upper - middle;
        base -= in2;
        final var difference = 2 * C6 * (in5 - in3);
        shortBlock[0] = base - difference;
        shortBlock[5] = base - difference;
        shortBlock[6] = base + difference;
        shortBlock[11] = base + difference;
    }

    // The subbands above the last with lines pass their overlap on and clear it.
    private void copyZeroBands(final int sublimit) {
        for (var subband = sublimit; subband < SUBBANDS; subband++) {
            final var start = bufferStart(subband);
            for (var instant = 0; instant < LINES; instant++) {
                subbandSamples[instant * SUBBANDS + subband] = overlap[start + GROUP * instant];
                overlap[start + GROUP * instant] = 0;
            }
        }
    }

    private static float[][] windows() {
        final var windows = new float[2 * GROUP][WINDOW_SIZE];
        for (var index = 0; index < 36; index++) {
            for (var type = 0; type < GROUP; type++) {
                if (type == SHORT_BLOCK_TYPE && index % 3 != 1) {
                    continue;
                }
                final var coefficient = (float) (windowValue(index, type) / (1 << 5));
                if (type == SHORT_BLOCK_TYPE) {
                    windows[type][index / 3] = coefficient;
                } else {
                    windows[type][windowPosition(index)] = coefficient;
                }
            }
        }
        for (var type = 0; type < GROUP; type++) {
            for (var index = 0; index < WINDOW_SIZE; index += 2) {
                windows[type + GROUP][index] = windows[type][index];
                windows[type + GROUP][index + 1] = -windows[type][index + 1];
            }
        }
        return windows;
    }

    // The last block half's coefficients are kept from {@code WINDOW_HALF}.
    private static int windowPosition(final int index) {
        if (index < LINES) {
            return index;
        }
        return index + WINDOW_HALF - LINES;
    }

    // The sine window of the block type, with the last stage of the IMDCT merged into it.
    private static double windowValue(final int index, final int type) {
        var value = Math.sin(Math.PI * (index + 0.5) / 36.0);
        if (type == 1) {
            if (index >= 30) {
                value = 0;
            } else if (index >= 24) {
                value = Math.sin(Math.PI * (index - 18 + 0.5) / 12.0);
            } else if (index >= 18) {
                value = 1;
            }
        } else if (type == 3) {
            if (index < 6) {
                value = 0;
            } else if (index < 12) {
                value = Math.sin(Math.PI * (index - 6 + 0.5) / 12.0);
            } else if (index < 18) {
                value = 1;
            }
        }
        return value * (0.5 * SCALAR / Math.cos(Math.PI * (2 * index + 19) / 72));
    }

    private static float[][][] groupWindows() {
        final var groupWindows = new float[2][GROUP][4 * WINDOW_SIZE];
        for (var type = 0; type < GROUP; type++) {
            for (var index = 0; index < WINDOW_SIZE; index++) {
                groupWindows[0][type][4 * index] = WINDOWS[type][index];
                groupWindows[0][type][4 * index + 1] = WINDOWS[type + GROUP][index];
                groupWindows[0][type][4 * index + 2] = WINDOWS[type][index];
                groupWindows[0][type][4 * index + 3] = WINDOWS[type + GROUP][index];
                groupWindows[1][type][4 * index] = WINDOWS[0][index];
                groupWindows[1][type][4 * index + 1] = WINDOWS[GROUP][index];
                groupWindows[1][type][4 * index + 2] = WINDOWS[type][index];
                groupWindows[1][type][4 * index + 3] = WINDOWS[type + GROUP][index];
            }
        }
        return groupWindows;
    }
}
