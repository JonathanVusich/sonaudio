package dev.javax.sonaudio.format.opus;

import lombok.experimental.UtilityClass;

/**
 * The float operations on the vectors of one band that CELT's shape decoding is made of (RFC 6716 section 4.3.4):
 * the normalisation, the spreading rotation, the Haar and Hadamard transforms that trade time for frequency
 * resolution, and the stereo merge. They work on a band's slice of an array, and keep FFmpeg's order of float and
 * double arithmetic.
 */
@UtilityClass
class CeltVectorUtils {

    private static final double SQUARE_ROOT_OF_HALF = 0.70710678118654752440;
    private static final float NORM_FLOOR = 1e-15f;
    private static final float MERGE_ENERGY_FLOOR = 6e-4f;
    private static final int ROTATION_BASE = 20;
    private static final int ROTATION_STEP = 5;
    private static final int SHORT_BLOCK_MINIMUM_STRIDES = 8;

    /**
     * How a band's short blocks are ordered when they are interleaved: by the Hadamard order of RFC 6716, which long
     * blocks use, or as they are.
     */
    enum BlockOrder {
        HADAMARD,
        SEQUENTIAL
    }

    /**
     * Scales a vector to a gain, with a floor under its norm so that a zero vector stays finite.
     *
     * @param vector the array the vector is in
     * @param start the index of its first entry
     * @param length how many entries
     * @param gain the norm to give it
     */
    static void renormalize(final float[] vector, final int start, final int length, final float gain) {
        var norm = NORM_FLOOR;
        for (var index = 0; index < length; index++) {
            norm += vector[start + index] * vector[start + index];
        }
        final var scale = gain / (float) Math.sqrt(norm);
        for (var index = 0; index < length; index++) {
            vector[start + index] *= scale;
        }
    }

    /**
     * Writes the pulses scaled by a gain.
     *
     * @param pulses the signed pulses of each entry
     * @param vector the array to write to
     * @param start the index of the first entry
     * @param length how many entries
     * @param gain the scale
     */
    static void scalePulses(
            final int[] pulses, final float[] vector, final int start, final int length, final float gain) {
        for (var index = 0; index < length; index++) {
            vector[start + index] = gain * pulses[index];
        }
    }

    /**
     * Spreads the energy of a band over its coefficients by rotations between entries a stride apart, the inverse of
     * what the encoder does, unless the band has too many pulses to be sparse (RFC 6716 section 4.3.4.3).
     *
     * @param vector the array the band is in
     * @param start the index of the band's first entry
     * @param length the band's size
     * @param blocks how many short blocks the band's entries are interleaved from
     * @param pulses how many pulses the band has
     * @param spread the frame's spread decision
     */
    static void spreadPulses(
            final float[] vector,
            final int start,
            final int length,
            final int blocks,
            final int pulses,
            final CeltSpread spread) {
        if (2 * pulses >= length || spread == CeltSpread.NONE) {
            return;
        }
        final var gain =
                (float) length / (float) (length + (ROTATION_BASE - ROTATION_STEP * spread.ordinal()) * pulses);
        final var theta = (float) (Math.PI * gain * gain / 4);
        final var cosine = (float) Math.cos(theta);
        final var sine = (float) Math.sin(theta);
        final var coarseStride = coarseStride(length, blocks);
        final var blockLength = length / blocks;
        for (var block = 0; block < blocks; block++) {
            final var blockStart = start + block * blockLength;
            if (coarseStride != 0) {
                rotate(vector, blockStart, blockLength, coarseStride, sine, cosine);
            }
            rotate(vector, blockStart, blockLength, 1, cosine, sine);
        }
    }

    // About the square root of the block length, rounded, when the band has at least 8 entries per block, and
    // otherwise 0 for no coarse rotation.
    private static int coarseStride(final int length, final int blocks) {
        if (length < blocks * SHORT_BLOCK_MINIMUM_STRIDES) {
            return 0;
        }
        var stride = 1;
        while ((stride * stride + stride) * blocks + (blocks >> 2) < length) {
            stride++;
        }
        return stride;
    }

    // One pass of the rotation forwards over the entries, then one backwards.
    private static void rotate(
            final float[] vector,
            final int start,
            final int length,
            final int stride,
            final float cosine,
            final float sine) {
        for (var index = 0; index < length - stride; index++) {
            rotatePair(vector, start + index, stride, cosine, sine);
        }
        for (var index = length - 2 * stride - 1; index >= 0; index--) {
            rotatePair(vector, start + index, stride, cosine, sine);
        }
    }

    private static void rotatePair(
            final float[] vector, final int first, final int stride, final float cosine, final float sine) {
        final var low = vector[first];
        final var high = vector[first + stride];
        vector[first + stride] = cosine * high + sine * low;
        vector[first] = cosine * low - sine * high;
    }

    /**
     * @param pulses the signed pulses of the band's entries, grouped by block
     * @param length how many entries
     * @param blocks how many blocks
     * @return a mask with a bit for each block that has a pulse
     */
    static int collapseMask(final int[] pulses, final int length, final int blocks) {
        if (blocks <= 1) {
            return 1;
        }
        final var blockLength = length / blocks;
        var mask = 0;
        for (var block = 0; block < blocks; block++) {
            for (var index = 0; index < blockLength; index++) {
                if (pulses[block * blockLength + index] != 0) {
                    mask |= 1 << block;
                }
            }
        }
        return mask;
    }

    /**
     * Turns a mid and a side vector, of which the mid has a gain, into the left and right channels (RFC 6716 section
     * 4.3.4.5), as the unit vectors of their sum and difference.
     *
     * @param first the array the mid vector is in, which the left channel replaces
     * @param firstStart the index of its first entry
     * @param second the array the side vector is in, which the right channel replaces
     * @param secondStart the index of its first entry
     * @param mid the gain of the mid vector
     * @param length how many entries
     */
    static void mergeStereo(
            final float[] first,
            final int firstStart,
            final float[] second,
            final int secondStart,
            final float mid,
            final int length) {
        var crossProduct = 0f;
        var sideEnergy = 0f;
        for (var index = 0; index < length; index++) {
            crossProduct += first[firstStart + index] * second[secondStart + index];
            sideEnergy += second[secondStart + index] * second[secondStart + index];
        }
        crossProduct *= mid;
        final var leftEnergy = mid * mid + sideEnergy - 2 * crossProduct;
        final var rightEnergy = mid * mid + sideEnergy + 2 * crossProduct;
        if (leftEnergy < MERGE_ENERGY_FLOOR || rightEnergy < MERGE_ENERGY_FLOOR) {
            System.arraycopy(first, firstStart, second, secondStart, length);
            return;
        }
        final var leftGain = 1.0f / (float) Math.sqrt(leftEnergy);
        final var rightGain = 1.0f / (float) Math.sqrt(rightEnergy);
        for (var index = 0; index < length; index++) {
            final var scaledMid = mid * first[firstStart + index];
            final var side = second[secondStart + index];
            first[firstStart + index] = leftGain * (scaledMid - side);
            second[secondStart + index] = rightGain * (scaledMid + side);
        }
    }

    /**
     * Gathers interleaved blocks back into time order: the inverse of {@link #interleaveBlocks}.
     *
     * @param scratch a buffer at least as long as the band
     * @param vector the array the band is in
     * @param start the index of the band's first entry
     * @param blockLength the entries of each block
     * @param blocks how many blocks
     * @param order how they are ordered
     */
    static void deinterleaveBlocks(
            final float[] scratch,
            final float[] vector,
            final int start,
            final int blockLength,
            final int blocks,
            final BlockOrder order) {
        final var orderStart = orderStart(order, blocks);
        for (var block = 0; block < blocks; block++) {
            final var target = CeltTables.HADAMARD_ORDER[orderStart + block];
            for (var index = 0; index < blockLength; index++) {
                scratch[target * blockLength + index] = vector[start + index * blocks + block];
            }
        }
        System.arraycopy(scratch, 0, vector, start, blockLength * blocks);
    }

    /**
     * Interleaves blocks that are in time order, entry by entry.
     *
     * @param scratch a buffer at least as long as the band
     * @param vector the array the band is in
     * @param start the index of the band's first entry
     * @param blockLength the entries of each block
     * @param blocks how many blocks
     * @param order how they are ordered
     */
    static void interleaveBlocks(
            final float[] scratch,
            final float[] vector,
            final int start,
            final int blockLength,
            final int blocks,
            final BlockOrder order) {
        final var orderStart = orderStart(order, blocks);
        for (var block = 0; block < blocks; block++) {
            final var source = CeltTables.HADAMARD_ORDER[orderStart + block];
            for (var index = 0; index < blockLength; index++) {
                scratch[index * blocks + block] = vector[start + source * blockLength + index];
            }
        }
        System.arraycopy(scratch, 0, vector, start, blockLength * blocks);
    }

    private static int orderStart(final BlockOrder order, final int blocks) {
        return switch (order) {
            case HADAMARD -> blocks - 2;
            case SEQUENTIAL -> 30;
        };
    }

    /**
     * One level of the Haar transform between neighbouring blocks, which doubles the time or frequency resolution of
     * a band.
     *
     * @param vector the array the band is in
     * @param start the index of the band's first entry
     * @param length the band's size
     * @param stride the distance between the entries that pair up
     */
    static void transformHaar(final float[] vector, final int start, final int length, final int stride) {
        final var pairs = length >> 1;
        for (var lane = 0; lane < stride; lane++) {
            for (var pair = 0; pair < pairs; pair++) {
                final var firstIndex = start + stride * (2 * pair) + lane;
                final var secondIndex = start + stride * (2 * pair + 1) + lane;
                final var first = vector[firstIndex];
                final var second = vector[secondIndex];
                vector[firstIndex] = (float) ((first + second) * SQUARE_ROOT_OF_HALF);
                vector[secondIndex] = (float) ((first - second) * SQUARE_ROOT_OF_HALF);
            }
        }
    }
}
