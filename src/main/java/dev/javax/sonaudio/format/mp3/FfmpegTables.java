package dev.javax.sonaudio.format.mp3;

import lombok.experimental.UtilityClass;

/**
 * The tables FFmpeg's floating-point MP3 decoder works from, built as it builds them, so that the floats it decodes
 * to come out of Sona's decoder too. Its values are kept as if the full scale were 2^23, and its synthesis window
 * scales them back down.
 */
@UtilityClass
final class FfmpegTables {

    /**
     * How many exponents (in steps of 2^(1/4)) the requantization tables have.
     */
    static final int EXPONENTS = 512;

    // The values a table of Huffman codes goes up to, and the largest 15 plus linbits can make.
    private static final int SMALL_VALUES = 16;
    private static final int LARGEST_VALUE = 8191 + 16;
    private static final double IMDCT_SCALAR = 1.759;
    private static final double[] EXP2_STEPS = {
        1.00000000000000000000, 1.18920711500272106672, Math.sqrt(2), 1.68179283050742908606
    };
    // 2^-72, where the exponents begin.
    private static final double EXPONENT_BASE = Math.scalb(1.0, -72);
    private static final int FRACTION_BITS = 23;
    private static final int MANTISSA_BITS = 31;
    private static final int TABLE_SIZE = LARGEST_VALUE * 4;

    private static final float[][] REQUANTIZED = requantizedValues();
    private static final byte[] LARGE_EXPONENTS = new byte[TABLE_SIZE];
    private static final int[] LARGE_MANTISSAS = new int[TABLE_SIZE];

    static {
        buildLargeValues();
    }

    /**
     * @param exponent in steps of 2^(1/4), from 0 to {@link #EXPONENTS} - 1
     * @param value from 0 to 15
     * @return value^(4/3) at the exponent, in the float FFmpeg's table holds
     */
    static float requantized(final int exponent, final int value) {
        return REQUANTIZED[exponent][value];
    }

    /**
     * @return the requantized value of 1 at the exponent, which count1 values take
     */
    static float requantizedOne(final int exponent) {
        return REQUANTIZED[exponent][1];
    }

    /**
     * Requantizes a value of 15 or more, as FFmpeg does, in whole numbers: the table's mantissa shifted by the
     * exponent, rounded, so values too small for that come out as 0.
     *
     * @param value from 15 to {@code 8191 + 15}
     */
    static int requantizedLarge(final int value, final int exponent) {
        final var mantissa = LARGE_MANTISSAS[4 * value + (exponent & 3)] & 0xFFFFFFFFL;
        final var shift = LARGE_EXPONENTS[4 * value + (exponent & 3)] - (exponent >> 2);
        if (shift < 0 || shift > 31) {
            return 0;
        }
        return (int) ((mantissa + ((1L << shift) >> 1)) >> shift);
    }

    private static float[][] requantizedValues() {
        final var powers = new double[SMALL_VALUES];
        for (var value = 0; value < SMALL_VALUES; value++) {
            powers[value] = value * Math.cbrt(value);
        }
        final var table = new float[EXPONENTS][SMALL_VALUES];
        var base = EXPONENT_BASE;
        for (var exponent = 0; exponent < EXPONENTS; exponent++) {
            if (exponent != 0 && (exponent & 3) == 0) {
                base *= 2;
            }
            final var scale = base * EXP2_STEPS[exponent & 3] / IMDCT_SCALAR;
            for (var value = 0; value < SMALL_VALUES; value++) {
                table[exponent][value] = (float) (powers[value] * scale);
            }
        }
        return table;
    }

    private static void buildLargeValues() {
        var power = 0.0;
        for (var index = 1; index < TABLE_SIZE; index++) {
            final double value = index / 4;
            if ((index & 3) == 0) {
                power = value / IMDCT_SCALAR * Math.cbrt(value);
            }
            final var product = power * EXP2_STEPS[index & 3];
            final var binaryExponent = Math.getExponent(product) + 1;
            final var fraction = Math.scalb(product, -binaryExponent);
            final var mantissa = (long) Math.rint(Math.scalb(fraction, MANTISSA_BITS));
            LARGE_MANTISSAS[index] = (int) mantissa;
            LARGE_EXPONENTS[index] = (byte) -(binaryExponent + FRACTION_BITS - MANTISSA_BITS + 5 - 100);
        }
    }
}
