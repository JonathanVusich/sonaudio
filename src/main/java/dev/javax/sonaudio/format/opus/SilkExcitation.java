package dev.javax.sonaudio.format.opus;

import java.util.Arrays;

/**
 * Decodes the excitation signal of a SILK frame (RFC 6716 section 4.2.7.8): in blocks of 16 samples, how many pulses
 * each block has, where they are by repeatedly halving the block, their low bits and their signs, and then the
 * pseudorandom sign flips that the frame's seed starts.
 *
 * <p>The samples go to a buffer that the class keeps and rewrites for every frame.
 */
final class SilkExcitation {

    private static final int BLOCK_SIZE = 16;
    private static final int MAX_BLOCKS = 20;
    private static final int ESCAPE_COUNT = 17;
    private static final int MAX_ESCAPES = 10;
    private static final int LAST_RATE_LEVEL = 10;
    private static final int ESCAPE_RATE_LEVEL = 9;
    private static final int MAX_SIGN_CONTEXT = 6;
    private static final int PULSE_STEP = 256;
    private static final int PULSE_ADJUSTMENT = 20;
    private static final int SEED_MULTIPLIER = 196314165;
    private static final int SEED_INCREMENT = 907633515;
    private static final float Q23_ONE = 8388608.0f;
    private static final int MODEL_BITS = 8;

    private final int[] pulses = new int[MAX_BLOCKS * BLOCK_SIZE];
    private final int[] pulseCounts = new int[MAX_BLOCKS];
    private final int[] lowBitCounts = new int[MAX_BLOCKS];
    private final float[] samples = new float[MAX_BLOCKS * BLOCK_SIZE];

    /**
     * Decodes one SILK frame's excitation.
     *
     * @param rangeDecoder the range decoder, positioned at the excitation's seed
     * @param layout the frame's shape, which says how many blocks there are
     * @param signalType the frame's signal type
     * @param offset the frame's quantisation offset
     */
    void decode(
            final RangeDecoder rangeDecoder,
            final SilkLayout layout,
            final SilkSignalType signalType,
            final QuantizationOffset offset) {
        var seed = rangeDecoder.decodeIcdf(SilkModels.LCG_SEED, MODEL_BITS);
        final var blocks = SilkTables.SHELL_BLOCKS[layout.bandwidth().ordinal()][layout.subframes() >> 2];
        final var rateLevel = rangeDecoder.decodeIcdf(SilkModels.EXC_RATE[signalType.voicedIndex()], MODEL_BITS);
        readPulseCounts(rangeDecoder, blocks, rateLevel);
        readPulseLocations(rangeDecoder, blocks);
        readLowBits(rangeDecoder, blocks);
        readSigns(rangeDecoder, blocks, signalType, offset);
        for (var index = 0; index < blocks * BLOCK_SIZE; index++) {
            seed = assemble(index, signalType, offset, seed);
        }
    }

    /**
     * @return the excitation the last call decoded, as Q23 values divided into units, in a buffer that the next call
     *     rewrites
     */
    float[] samples() {
        return samples;
    }

    // How many pulses each block has. A count of 17 means more than 16, and is followed by further counts, each
    // adding a low bit to every pulse of the block, until a count under 17 or ten of them; the tenth is read from
    // the table that has no escape.
    private void readPulseCounts(final RangeDecoder rangeDecoder, final int blocks, final int rateLevel) {
        Arrays.fill(lowBitCounts, 0, blocks, 0);
        for (var block = 0; block < blocks; block++) {
            var count = rangeDecoder.decodeIcdf(SilkModels.PULSE_COUNT[rateLevel], MODEL_BITS);
            while (count == ESCAPE_COUNT && ++lowBitCounts[block] != MAX_ESCAPES) {
                count = rangeDecoder.decodeIcdf(SilkModels.PULSE_COUNT[ESCAPE_RATE_LEVEL], MODEL_BITS);
            }
            if (lowBitCounts[block] == MAX_ESCAPES) {
                count = rangeDecoder.decodeIcdf(SilkModels.PULSE_COUNT[LAST_RATE_LEVEL], MODEL_BITS);
            }
            pulseCounts[block] = count;
        }
    }

    private void readPulseLocations(final RangeDecoder rangeDecoder, final int blocks) {
        for (var block = 0; block < blocks; block++) {
            final var start = block * BLOCK_SIZE;
            if (pulseCounts[block] == 0) {
                Arrays.fill(pulses, start, start + BLOCK_SIZE, 0);
            } else {
                splitPulses(rangeDecoder, 0, pulseCounts[block], start, BLOCK_SIZE);
            }
        }
    }

    // Splits the pulses of a run of samples between its two halves, and then each half between its own, down to pairs:
    // the model of each level knows how many pulses were in the run.
    private void splitPulses(
            final RangeDecoder rangeDecoder, final int level, final int total, final int start, final int width) {
        if (total == 0) {
            Arrays.fill(pulses, start, start + width, 0);
            return;
        }
        final var table = SilkModels.PULSE_LOCATION[level][total - 1];
        final var first = rangeDecoder.decodeIcdf(table, MODEL_BITS);
        final var second = total - first;
        if (width == 2) {
            pulses[start] = first;
            pulses[start + 1] = second;
            return;
        }
        splitPulses(rangeDecoder, level + 1, first, start, width / 2);
        splitPulses(rangeDecoder, level + 1, second, start + width / 2, width / 2);
    }

    private void readLowBits(final RangeDecoder rangeDecoder, final int blocks) {
        for (var index = 0; index < blocks * BLOCK_SIZE; index++) {
            for (var bit = 0; bit < lowBitCounts[index / BLOCK_SIZE]; bit++) {
                pulses[index] = (pulses[index] << 1) | rangeDecoder.decodeIcdf(SilkModels.EXCITATION_LSB, MODEL_BITS);
            }
        }
    }

    private void readSigns(
            final RangeDecoder rangeDecoder,
            final int blocks,
            final SilkSignalType signalType,
            final QuantizationOffset offset) {
        final var models = SilkModels.EXCITATION_SIGN[signalType.typeIndex()][offset.ordinal()];
        for (var index = 0; index < blocks * BLOCK_SIZE; index++) {
            if (pulses[index] != 0) {
                final var context = Math.min(pulseCounts[index / BLOCK_SIZE], MAX_SIGN_CONTEXT);
                if (rangeDecoder.decodeIcdf(models[context], MODEL_BITS) == 0) {
                    pulses[index] = -pulses[index];
                }
            }
        }
    }

    // Turns a sample's pulse count into its Q23 value: scaled up, offset towards zero, and negated if the seed's
    // generator says so, and returns the generator's next state.
    private int assemble(
            final int index, final SilkSignalType signalType, final QuantizationOffset offset, final int seed) {
        final var pulse = pulses[index];
        var value = pulse * PULSE_STEP | SilkTables.QUANT_OFFSET[signalType.voicedIndex()][offset.ordinal()];
        if (pulse < 0) {
            value += PULSE_ADJUSTMENT;
        } else if (pulse > 0) {
            value -= PULSE_ADJUSTMENT;
        }
        final var next = SEED_MULTIPLIER * seed + SEED_INCREMENT;
        if (next < 0) {
            value = -value;
        }
        samples[index] = value / Q23_ONE;
        return next + pulse;
    }
}
