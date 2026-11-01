package dev.javax.sonaudio.format.mp3;

import java.util.Arrays;

/**
 * The inverse MDCT of Layer III's hybrid filterbank, for one channel (ISO/IEC 11172-3 section 2.4.3.4.10), worked
 * out from its formula in doubles: each subband's 18 lines become 36 windowed samples, whose first half is added to
 * the second half of the subband's last block. A long block is one 36-point transform, and a short block three
 * 12-point ones that overlap within it. Odd subbands have their odd samples negated, for the synthesis filterbank. The
 * decoder keeps one per channel, as each granule overlaps the one before.
 */
final class Imdct implements SubbandSamples {

    private static final int SUBBANDS = Spectrum.SUBBANDS;
    private static final int LINES = Spectrum.SUBBAND_LINES;
    private static final int LONG_SIZE = 2 * LINES;
    private static final int SHORT_LINES = LINES / Windowing.WINDOWS;
    private static final int SHORT_SIZE = 2 * SHORT_LINES;
    // A short block's three windows start at samples 6, 12 and 18.
    private static final int SHORT_START = SHORT_LINES;
    // A mixed block's two lowest subbands are long blocks with the normal window, whatever its block type.
    private static final int MIXED_LONG_SUBBANDS = 2;
    // The requantized lines come out 32 times the 2^23 full scale the synthesis filterbank takes.
    private static final double SCALE = 1.0 / 32;

    private static final double[][] LONG_COSINES = cosines(LONG_SIZE);
    private static final double[][] SHORT_COSINES = cosines(SHORT_SIZE);
    private static final double[][] LONG_WINDOWS = longWindows();
    private static final double[] SHORT_WINDOW = shortWindow();

    // The previous blocks' second halves, subband by subband.
    private final double[] overlap = new double[SUBBANDS * LINES];
    // The granule's subband samples: sample t of subband s at t * 32 + s, as the synthesis filterbank takes each
    // instant's subbands together.
    private final float[] subbandSamples = new float[SUBBANDS * LINES];
    private final double[] block = new double[LONG_SIZE];

    /**
     * Transforms a granule's spectrum, which must have been reordered and had its aliasing reduced.
     */
    void transform(final Spectrum spectrum, final Windowing windowing) {
        final var blockType = windowing.blockType();
        final var lowSubbands = windowing.lowSubbands();
        for (var subband = 0; subband < SUBBANDS; subband++) {
            final var subbandType = subbandBlockType(subband, blockType, lowSubbands);
            if (subbandType == BlockType.SHORT) {
                shortBlock(spectrum, subband);
            } else {
                longBlock(spectrum, subband, subbandType);
            }
            overlapAdd(subband);
        }
    }

    /**
     * @param instant from 0 to 17
     * @return the subband's sample at the instant, from the granule last transformed
     */
    @Override
    public float subbandSample(final int instant, final int subband) {
        return subbandSamples[instant * SUBBANDS + subband];
    }

    private static BlockType subbandBlockType(
            final int subband, final BlockType blockType, final LowSubbands lowSubbands) {
        if (lowSubbands == LowSubbands.NORMAL_WINDOW && subband < MIXED_LONG_SUBBANDS) {
            return BlockType.NORMAL;
        }
        return blockType;
    }

    private void longBlock(final Spectrum spectrum, final int subband, final BlockType blockType) {
        final var window = LONG_WINDOWS[blockType.ordinal()];
        final var firstLine = subband * LINES;
        for (var sample = 0; sample < LONG_SIZE; sample++) {
            var sum = 0.0;
            for (var line = 0; line < LINES; line++) {
                sum += spectrum.line(firstLine + line) * LONG_COSINES[sample][line];
            }
            block[sample] = sum * window[sample] * SCALE;
        }
    }

    // The three windows' lines are interleaved: window w's are every third from the subband's line w.
    private void shortBlock(final Spectrum spectrum, final int subband) {
        Arrays.fill(block, 0);
        final var firstLine = subband * LINES;
        for (var window = 0; window < Windowing.WINDOWS; window++) {
            final var start = SHORT_START + window * SHORT_LINES;
            for (var sample = 0; sample < SHORT_SIZE; sample++) {
                var sum = 0.0;
                for (var line = 0; line < SHORT_LINES; line++) {
                    sum += spectrum.line(firstLine + Windowing.WINDOWS * line + window) * SHORT_COSINES[sample][line];
                }
                block[start + sample] += sum * SHORT_WINDOW[sample] * SCALE;
            }
        }
    }

    private void overlapAdd(final int subband) {
        final var first = subband * LINES;
        for (var instant = 0; instant < LINES; instant++) {
            var sample = block[instant] + overlap[first + instant];
            overlap[first + instant] = block[LINES + instant];
            if ((subband & instant & 1) != 0) {
                sample = -sample;
            }
            subbandSamples[instant * SUBBANDS + subband] = (float) sample;
        }
    }

    // cos(pi / (2n) * (2i + 1 + n / 2) * (2k + 1)) for an n-point transform's sample i and line k.
    private static double[][] cosines(final int size) {
        final var cosines = new double[size][size / 2];
        for (var sample = 0; sample < size; sample++) {
            for (var line = 0; line < size / 2; line++) {
                cosines[sample][line] =
                        Math.cos(Math.PI / (2 * size) * (2 * sample + 1 + size / 2.0) * (2 * line + 1));
            }
        }
        return cosines;
    }

    // The sine windows of the normal, start and stop blocks (section 2.4.3.4.10.3), by block type's ordinal.
    private static double[][] longWindows() {
        final var windows = new double[BlockType.values().length][LONG_SIZE];
        for (var sample = 0; sample < LONG_SIZE; sample++) {
            final var sine = Math.sin(Math.PI / LONG_SIZE * (sample + 0.5));
            windows[BlockType.NORMAL.ordinal()][sample] = sine;
            windows[BlockType.START.ordinal()][sample] = startWindow(sample, sine);
            windows[BlockType.STOP.ordinal()][sample] = stopWindow(sample, sine);
        }
        return windows;
    }

    private static double startWindow(final int sample, final double sine) {
        if (sample < 18) {
            return sine;
        }
        if (sample < 24) {
            return 1;
        }
        if (sample < 30) {
            return Math.sin(Math.PI / SHORT_SIZE * (sample - 18 + 0.5));
        }
        return 0;
    }

    private static double stopWindow(final int sample, final double sine) {
        if (sample < 6) {
            return 0;
        }
        if (sample < 12) {
            return Math.sin(Math.PI / SHORT_SIZE * (sample - 6 + 0.5));
        }
        if (sample < 18) {
            return 1;
        }
        return sine;
    }

    private static double[] shortWindow() {
        final var window = new double[SHORT_SIZE];
        for (var sample = 0; sample < SHORT_SIZE; sample++) {
            window[sample] = Math.sin(Math.PI / SHORT_SIZE * (sample + 0.5));
        }
        return window;
    }
}
