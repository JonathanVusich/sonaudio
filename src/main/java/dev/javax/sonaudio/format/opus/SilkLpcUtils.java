package dev.javax.sonaudio.format.opus;

import lombok.experimental.UtilityClass;

/**
 * The fixed-point steps that {@link SilkLpcConverter} is made of, as FFmpeg's {@code silk.c} does them.
 */
@UtilityClass
final class SilkLpcUtils {

    /**
     * The most coefficients a filter has: wideband's.
     */
    static final int MAX_ORDER = 16;

    private static final int NARROWBAND_ORDER = 10;
    private static final int COSINE_STEP_BITS = 8;
    private static final int COSINE_STEP_MASK = 255;
    private static final int COSINE_ROUNDING = 4;

    /**
     * @return the product of the two numbers shifted right by {@code shift}, rounded to the nearest, as FFmpeg's
     *     {@code ROUND_MULL} does it in 64 bits
     */
    static long roundedProduct(final long first, final long second, final int shift) {
        return (((first * second) >> (shift - 1)) + 1) >> 1;
    }

    /**
     * @return the high 32 bits of the 64-bit product of the two numbers, as FFmpeg's {@code MULH}
     */
    static int highProduct(final int first, final int second) {
        return (int) (((long) first * second) >> 32);
    }

    /**
     * @param frequency a line spectral frequency in Q15, from 0 to 32767
     * @return 2 * cos(frequency) in Q17, by linear interpolation between the table's entries, which are 1 / 128 of a
     *     half circle apart
     */
    static int cosine(final int frequency) {
        final var index = frequency >> COSINE_STEP_BITS;
        final var offset = frequency & COSINE_STEP_MASK;
        final var base = SilkTables.COSINE[index];
        final var interpolated = base * 256 + (SilkTables.COSINE[index + 1] - base) * offset;
        return (interpolated + COSINE_ROUNDING) >> 3;
    }

    /**
     * @param order 10 or 16
     * @param index the frequency's index, from 0
     * @return the place of the frequency among the roots of the two polynomials
     */
    static int ordering(final int order, final int index) {
        if (order == NARROWBAND_ORDER) {
            return SilkTables.LSF_ORDERING_NBMB[index];
        }
        return SilkTables.LSF_ORDERING_WB[index];
    }
}
