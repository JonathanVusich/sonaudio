package dev.javax.sonaudio.format.alac;

import java.math.BigInteger;
import lombok.experimental.UtilityClass;

/**
 * The arithmetic ALAC's decoder and its file's timing share: fixed-width integers, and rescaling times.
 */
@UtilityClass
final class AlacUtils {

    /**
     * @param value the bits to read as a signed number
     * @param bits how many of the value's low bits make the number, 1 to 32
     * @return the number the low bits hold, sign-extended
     */
    public static int signExtend(final int value, final int bits) {
        final var shift = Integer.SIZE - bits;
        return (value << shift) >> shift;
    }

    /**
     * @param value any 32-bit value, taken as unsigned
     * @return the position of its highest set bit, 0 for 0 as well as 1
     */
    public static int floorLog2(final int value) {
        return Integer.SIZE - 1 - Integer.numberOfLeadingZeros(value | 1);
    }

    /**
     * Works out {@code value * numerator / denominator}, rounded to nearest with halves away from zero, without
     * overflowing.
     *
     * @param value the quantity to scale
     * @param numerator what it is scaled up by
     * @param denominator what it is scaled down by, which is positive
     * @return the scaled quantity
     */
    public static long rescale(final long value, final long numerator, final long denominator) {
        if (value < 0) {
            return -rescale(-value, numerator, denominator);
        }
        final var scaled = BigInteger.valueOf(value).multiply(BigInteger.valueOf(numerator));
        final var half = BigInteger.valueOf(denominator / 2);
        return scaled.add(half).divide(BigInteger.valueOf(denominator)).longValue();
    }
}
