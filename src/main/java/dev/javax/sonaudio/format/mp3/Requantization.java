package dev.javax.sonaudio.format.mp3;

import lombok.experimental.UtilityClass;

/**
 * Layer III's requantization of a Huffman-coded value (ISO/IEC 11172-3 section 2.4.3.4.7.1): value^(4/3), scaled by a
 * power of 2^(1/4). The values come out 32 times the 2^23 full scale the synthesis filterbank takes, which the IMDCT
 * scales back.
 */
@UtilityClass
final class Requantization {

    // A table of Huffman codes goes up to 15, and linbits add up to 8191 more.
    private static final int LARGEST_VALUE = 8191 + 16;
    private static final int EXPONENTS = 512;
    // The exponent counts in steps of 2^(1/4) from 2^-72.
    private static final int EXPONENT_BASE = 72;
    private static final double[] POWERS = powers();
    private static final double[] SCALES = scales();

    /**
     * @param exponent in steps of 2^(1/4), from 0 to 511
     * @param value from 0 to {@code 8191 + 15}
     * @return value^(4/3) at the exponent
     */
    static float requantized(final int exponent, final int value) {
        return (float) (POWERS[value] * SCALES[exponent]);
    }

    private static double[] powers() {
        final var powers = new double[LARGEST_VALUE];
        for (var value = 0; value < LARGEST_VALUE; value++) {
            powers[value] = value * Math.cbrt(value);
        }
        return powers;
    }

    private static double[] scales() {
        final var scales = new double[EXPONENTS];
        for (var exponent = 0; exponent < EXPONENTS; exponent++) {
            scales[exponent] = Math.pow(2, exponent / 4.0 - EXPONENT_BASE);
        }
        return scales;
    }
}
