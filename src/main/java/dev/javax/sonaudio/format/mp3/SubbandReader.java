package dev.javax.sonaudio.format.mp3;

import dev.javax.bitstream.BitInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Reads a Layer I or II frame's subband samples, which they code directly (ISO/IEC 11172-3 sections 2.4.1.5, 2.4.1.6,
 * 2.4.3.2 and 2.4.3.3, ISO/IEC 13818-3 section 2.4.3.3): each subband's bit allocation, its scale factors, then its
 * samples, requantized. In joint stereo, the subbands from the bound the mode extension gives up share their
 * allocation and samples between the channels, which only their scale factors set apart. The decoder keeps one, and
 * reads each frame into it in place.
 */
final class SubbandReader {

    private static final int SUBBANDS = Spectrum.SUBBANDS;
    private static final int MAX_CHANNELS = 2;
    // A Layer I frame has 12 instants, and a Layer II frame three parts of 12, each coded in groups of three.
    private static final int PART_INSTANTS = 12;
    private static final int LAYER_II_PARTS = 3;
    private static final int GROUP = 3;
    private static final int LAYER_I_ALLOCATION_BITS = 4;
    private static final int SCALE_FACTOR_BITS = 6;
    // Layer II's quantization classes, by index: how many steps each has, and how many bits code a sample, or three
    // samples together where it's negative.
    private static final int[] STEPS = {
        3, 5, 7, 9, 15, 31, 63, 127, 255, 511, 1023, 2047, 4095, 8191, 16383, 32767, 65535
    };
    private static final int[] SAMPLE_BITS = {-5, -7, 3, -10, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
    // A scale factor's index i scales by 2^(1 - i / 3).
    // FFmpeg's floating-point decoder holds subband samples with a full scale of 2^23.
    private static final double FULL_SCALE = 1 << 23;
    private static final double[] SCALES =
            IntStream.range(0, 64).mapToDouble(i -> Math.pow(2, 1 - i / 3.0)).toArray();

    private final double[][] samples = new double[MAX_CHANNELS][LAYER_II_PARTS * PART_INSTANTS * SUBBANDS];
    private final SubbandSamples[] channels = {
        (instant, subband) -> (float) (samples[0][instant * SUBBANDS + subband] * FULL_SCALE),
        (instant, subband) -> (float) (samples[1][instant * SUBBANDS + subband] * FULL_SCALE)
    };
    private final int[][] allocations = new int[MAX_CHANNELS][SUBBANDS];
    private final int[][] classes = new int[MAX_CHANNELS][SUBBANDS];
    // Layer II's scale factor selection for each subband.
    private final int[][] selections = new int[MAX_CHANNELS][SUBBANDS];
    // Each subband's scale factor for each part: Layer I's one, or Layer II's three.
    private final int[][][] scaleFactors = new int[MAX_CHANNELS][SUBBANDS][LAYER_II_PARTS];

    /**
     * @param channel 0 for the left or only channel, and 1 for the right
     * @return the channel's subband samples, of the frame last read
     */
    SubbandSamples channel(final int channel) {
        return channels[channel];
    }

    /**
     * Reads a Layer I frame's 12 instants of samples.
     *
     * @param bits the frame's stream, after its header and CRC
     */
    void readLayerI(final BitInputStream bits, final FrameHeader header) throws IOException {
        final var channelCount = header.channelCount();
        final var bound = bound(header, SUBBANDS);
        for (var subband = 0; subband < SUBBANDS; subband++) {
            for (var channel = 0; channel < channelCount; channel++) {
                if (subband < bound || channel == 0) {
                    allocations[channel][subband] = (int) bits.readBits(LAYER_I_ALLOCATION_BITS);
                } else {
                    allocations[channel][subband] = allocations[0][subband];
                }
            }
        }
        for (var subband = 0; subband < SUBBANDS; subband++) {
            for (var channel = 0; channel < channelCount; channel++) {
                if (allocations[channel][subband] != 0) {
                    scaleFactors[channel][subband][0] = (int) bits.readBits(SCALE_FACTOR_BITS);
                }
            }
        }
        for (var instant = 0; instant < PART_INSTANTS; instant++) {
            for (var subband = 0; subband < SUBBANDS; subband++) {
                readLayerISample(bits, channelCount, bound, instant, subband);
            }
        }
    }

    // An allocation of n codes each sample in n + 1 bits, so 2^(n + 1) - 1 steps.
    private void readLayerISample(
            final BitInputStream bits, final int channelCount, final int bound, final int instant, final int subband)
            throws IOException {
        for (var channel = 0; channel < channelCount; channel++) {
            final var allocation = allocations[channel][subband];
            if (allocation == 0) {
                samples[channel][instant * SUBBANDS + subband] = 0;
                continue;
            }
            final var sampleBits = allocation + 1;
            if (subband < bound || channel == 0) {
                final var value = bits.readBits(sampleBits);
                for (var shared = channel; shared < sharedChannels(subband, bound, channel, channelCount); shared++) {
                    samples[shared][instant * SUBBANDS + subband] =
                            requantize((int) value, (1 << sampleBits) - 1, scaleFactors[shared][subband][0]);
                }
            }
        }
    }

    /**
     * Reads a Layer II frame's 36 instants of samples, with the allocation table its bitrate and sample rate pick.
     *
     * @param bits the frame's stream, after its header and CRC
     */
    void readLayerII(final BitInputStream bits, final FrameHeader header) throws IOException {
        final var channelCount = header.channelCount();
        final var table = AllocationTable.of(header);
        final var subbandLimit = table.subbandLimit();
        final var bound = bound(header, subbandLimit);
        for (var subband = 0; subband < subbandLimit; subband++) {
            for (var channel = 0; channel < channelCount; channel++) {
                if (subband < bound || channel == 0) {
                    allocations[channel][subband] = (int) bits.readBits(table.allocationBits(subband));
                } else {
                    allocations[channel][subband] = allocations[0][subband];
                }
                classes[channel][subband] = table.quantizationClass(subband, allocations[channel][subband]);
            }
        }
        for (var subband = 0; subband < subbandLimit; subband++) {
            for (var channel = 0; channel < channelCount; channel++) {
                if (allocations[channel][subband] != 0) {
                    selections[channel][subband] = (int) bits.readBits(2);
                }
            }
        }
        for (var subband = 0; subband < subbandLimit; subband++) {
            for (var channel = 0; channel < channelCount; channel++) {
                if (allocations[channel][subband] != 0) {
                    readScaleFactors(bits, scaleFactors[channel][subband], selections[channel][subband]);
                }
            }
        }
        for (var channel = 0; channel < channelCount; channel++) {
            Arrays.fill(samples[channel], 0);
        }
        for (var part = 0; part < LAYER_II_PARTS; part++) {
            for (var group = 0; group < PART_INSTANTS; group += GROUP) {
                final var instant = part * PART_INSTANTS + group;
                for (var subband = 0; subband < subbandLimit; subband++) {
                    readLayerIIGroup(bits, channelCount, bound, part, instant, subband);
                }
            }
        }
    }

    // The scale factor selection says which of the three parts share a scale factor: none, the first two, all three,
    // or the last two.
    private static void readScaleFactors(final BitInputStream bits, final int[] factors, final int selection)
            throws IOException {
        factors[0] = (int) bits.readBits(SCALE_FACTOR_BITS);
        switch (selection) {
            case 0 -> {
                factors[1] = (int) bits.readBits(SCALE_FACTOR_BITS);
                factors[2] = (int) bits.readBits(SCALE_FACTOR_BITS);
            }
            case 1 -> {
                factors[1] = factors[0];
                factors[2] = (int) bits.readBits(SCALE_FACTOR_BITS);
            }
            case 2 -> {
                factors[1] = factors[0];
                factors[2] = factors[0];
            }
            default -> {
                factors[2] = (int) bits.readBits(SCALE_FACTOR_BITS);
                factors[1] = factors[2];
            }
        }
    }

    // Three instants of a subband: their samples one after another, or coded together as one value in classes with 3,
    // 5 or 9 steps.
    private void readLayerIIGroup(
            final BitInputStream bits,
            final int channelCount,
            final int bound,
            final int part,
            final int instant,
            final int subband)
            throws IOException {
        for (var channel = 0; channel < channelCount; channel++) {
            if (allocations[channel][subband] == 0 || (subband >= bound && channel > 0)) {
                continue;
            }
            final var quantizationClass = classes[channel][subband];
            final var steps = STEPS[quantizationClass];
            final var sampleBits = SAMPLE_BITS[quantizationClass];
            var grouped = sampleBits < 0 ? bits.readBits(-sampleBits) : 0;
            for (var sample = 0; sample < GROUP; sample++) {
                final int value;
                if (sampleBits < 0) {
                    value = (int) (grouped % steps);
                    grouped /= steps;
                } else {
                    value = (int) bits.readBits(sampleBits);
                }
                for (var shared = channel; shared < sharedChannels(subband, bound, channel, channelCount); shared++) {
                    samples[shared][(instant + sample) * SUBBANDS + subband] =
                            requantize(value, steps, scaleFactors[shared][subband][part]);
                }
            }
        }
    }

    // The channels a channel's sample goes to: in joint stereo, from the bound up, the left channel's goes to both.
    private static int sharedChannels(final int subband, final int bound, final int channel, final int channelCount) {
        if (subband >= bound) {
            return channelCount;
        }
        return channel + 1;
    }

    // Joint stereo's mode extension puts the bound at subband 4, 8, 12 or 16. Other modes have none.
    private static int bound(final FrameHeader header, final int subbandLimit) {
        if (header.channelMode() != ChannelMode.JOINT_STEREO) {
            return subbandLimit;
        }
        return Math.min((header.modeExtension() + 1) * 4, subbandLimit);
    }

    // A sample of a class with this many steps is centred on its middle step, each step 2 / steps apart.
    private static double requantize(final int value, final int steps, final int scaleFactor) {
        return (value - steps / 2) * 2.0 / steps * SCALES[scaleFactor];
    }

    /**
     * One of Layer II's bit allocation tables (ISO/IEC 11172-3 Tables B.2a to B.2d, ISO/IEC 13818-3 Table B.1): for each
     * subband up to its limit, how many bits its allocation takes, and the quantization class each allocation picks.
     */
    private record AllocationTable(int[][] classes) {

        // Tables B.2a and B.2b, which differ only in their limit: 27 subbands or 30.
        private static final int[] A_FIRST = {0, 2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        private static final int[] A_SECOND = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 16};
        private static final int[] A_THIRD = {0, 1, 2, 3, 4, 5, 16};
        private static final int[] A_FOURTH = {0, 1, 16};
        private static final int[][] A =
                concat(rows(3, A_FIRST), rows(8, A_SECOND), rows(12, A_THIRD), rows(7, A_FOURTH));
        private static final AllocationTable B_2A = new AllocationTable(Arrays.copyOf(A, 27));
        private static final AllocationTable B_2B = new AllocationTable(A);
        // Tables B.2c and B.2d, for low bitrates, which likewise differ only in their limit: 8 subbands or 12.
        private static final int[] C_FIRST = {0, 1, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
        private static final int[] C_SECOND = {0, 1, 3, 4, 5, 6, 7};
        private static final int[][] C = concat(rows(2, C_FIRST), rows(10, C_SECOND));
        private static final AllocationTable B_2C = new AllocationTable(Arrays.copyOf(C, 8));
        private static final AllocationTable B_2D = new AllocationTable(C);
        // MPEG-2's Table B.1, for its lower sample rates.
        private static final int[] LSF_FIRST = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14};
        private static final int[] LSF_SECOND = {0, 1, 3, 4, 5, 6, 7};
        private static final int[] LSF_THIRD = {0, 1, 3};
        private static final AllocationTable LSF =
                new AllocationTable(concat(rows(4, LSF_FIRST), rows(7, LSF_SECOND), rows(19, LSF_THIRD)));

        /**
         * Picks the table as ISO/IEC 11172-3 Table B.2 does, by the bitrate per channel and sample rate, as FFmpeg does.
         */
        static AllocationTable of(final FrameHeader header) {
            if (header.version() != MpegVersion.MPEG_1) {
                return LSF;
            }
            final var channelBitrate = header.bitrate() / 1000 / header.channelCount();
            final var sampleRate = header.sampleRate();
            if ((sampleRate == 48000 && channelBitrate >= 56) || (channelBitrate >= 56 && channelBitrate <= 80)) {
                return B_2A;
            }
            if (sampleRate != 48000 && channelBitrate >= 96) {
                return B_2B;
            }
            if (sampleRate != 32000 && channelBitrate <= 48) {
                return B_2C;
            }
            return B_2D;
        }

        int subbandLimit() {
            return classes.length;
        }

        // Each subband's allocation picks one of 2^bits - 1 classes, or none for 0.
        int allocationBits(final int subband) {
            return Integer.numberOfTrailingZeros(classes[subband].length + 1);
        }

        int quantizationClass(final int subband, final int allocation) {
            if (allocation == 0) {
                return 0;
            }
            return classes[subband][allocation - 1];
        }

        private static int[][] concat(final int[][]... groups) {
            return Arrays.stream(groups).flatMap(Arrays::stream).toArray(int[][]::new);
        }

        private static int[][] rows(final int count, final int[] classes) {
            final var rows = new int[count][];
            Arrays.fill(rows, classes);
            return rows;
        }
    }
}
