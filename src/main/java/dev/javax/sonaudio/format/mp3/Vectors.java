package dev.javax.sonaudio.format.mp3;

import lombok.experimental.UtilityClass;

/**
 * The operations of x86's four-lane single-precision registers (SSE and AVX's 128-bit forms) that FFmpeg's assembly
 * IMDCT is written in, on arrays of four floats, so that it can be followed instruction by instruction and give the
 * same floats. Each takes its destination first, and may name a source as the destination.
 */
@UtilityClass
final class Vectors {

    static final int LANES = 4;

    static void set(
            final float[] destination, final float first, final float second, final float third, final float fourth) {
        destination[0] = first;
        destination[1] = second;
        destination[2] = third;
        destination[3] = fourth;
    }

    static void copy(final float[] destination, final float[] source) {
        System.arraycopy(source, 0, destination, 0, LANES);
    }

    static void add(final float[] destination, final float[] first, final float[] second) {
        for (var lane = 0; lane < LANES; lane++) {
            destination[lane] = first[lane] + second[lane];
        }
    }

    static void sub(final float[] destination, final float[] first, final float[] second) {
        for (var lane = 0; lane < LANES; lane++) {
            destination[lane] = first[lane] - second[lane];
        }
    }

    static void mul(final float[] destination, final float[] first, final float[] second) {
        for (var lane = 0; lane < LANES; lane++) {
            destination[lane] = first[lane] * second[lane];
        }
    }

    /**
     * Subtracts in the lanes where the sign mask says to add, and adds in the others (addsubps).
     */
    static void addSub(final float[] destination, final float[] first, final float[] second) {
        destination[0] = first[0] - second[0];
        destination[1] = first[1] + second[1];
        destination[2] = first[2] - second[2];
        destination[3] = first[3] + second[3];
    }

    /**
     * Clears the lanes whose bit the mask hasn't set (andps with a mask of all ones or all zeros in each lane).
     */
    static void keep(final float[] destination, final float[] source, final int kept) {
        for (var lane = 0; lane < LANES; lane++) {
            if ((kept >> lane & 1) == 1) {
                destination[lane] = source[lane];
            } else {
                destination[lane] = 0;
            }
        }
    }

    /**
     * Flips the sign of the lanes whose bit the mask sets (xorps with a sign bit in each).
     */
    static void negate(final float[] destination, final float[] source, final int flipped) {
        for (var lane = 0; lane < LANES; lane++) {
            if ((flipped >> lane & 1) == 1) {
                destination[lane] = -source[lane];
            } else {
                destination[lane] = source[lane];
            }
        }
    }

    /**
     * shufps: the low two lanes come from the first source and the high two from the second, each picked by two bits
     * of the immediate.
     */
    static void shuffle(final float[] destination, final float[] first, final float[] second, final int immediate) {
        final var lane0 = first[immediate & 3];
        final var lane1 = first[immediate >> 2 & 3];
        final var lane2 = second[immediate >> 4 & 3];
        final var lane3 = second[immediate >> 6 & 3];
        set(destination, lane0, lane1, lane2, lane3);
    }

    /**
     * movlhps: the low half of the first source, then the low half of the second.
     */
    static void lowHigh(final float[] destination, final float[] first, final float[] second) {
        set(destination, first[0], first[1], second[0], second[1]);
    }

    /**
     * movhlps: the high half of the second source, then the high half of the first.
     */
    static void highLow(final float[] destination, final float[] first, final float[] second) {
        set(destination, second[2], second[3], first[2], first[3]);
    }

    /**
     * unpcklps: the low halves of the sources, interleaved.
     */
    static void unpackLow(final float[] destination, final float[] first, final float[] second) {
        set(destination, first[0], second[0], first[1], second[1]);
    }

    /**
     * unpckhps: the high halves of the sources, interleaved.
     */
    static void unpackHigh(final float[] destination, final float[] first, final float[] second) {
        set(destination, first[2], second[2], first[3], second[3]);
    }

    /**
     * palignr by 12 bytes: the last lane of the first source, then the first three of the second.
     */
    static void rotateLeft(final float[] destination, final float[] first, final float[] second) {
        set(destination, first[3], second[0], second[1], second[2]);
    }

    /**
     * Reads four floats that are {@code stride} apart.
     */
    static void gather(final float[] destination, final float[] source, final int start, final int stride) {
        for (var lane = 0; lane < LANES; lane++) {
            destination[lane] = source[start + lane * stride];
        }
    }

    /**
     * Writes four floats that are {@code stride} apart.
     */
    static void scatter(final float[] destination, final int start, final int stride, final float[] source) {
        for (var lane = 0; lane < LANES; lane++) {
            destination[start + lane * stride] = source[lane];
        }
    }
}
