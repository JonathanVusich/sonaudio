package dev.javax.sonaudio.format.opus;

import lombok.experimental.UtilityClass;

/**
 * The integer arithmetic of CELT's pulse vectors (RFC 6716 section 4.3.4): how many ways there are to place a number
 * of pulses in a number of dimensions, which of those ways an index names, the bits each pulse count costs, and the
 * fixed-point trigonometry that splits a band's bits between its halves.
 */
@UtilityClass
class CeltPulseUtils {

    private static final int MAX_DIMENSIONS = 14;
    private static final int MAX_PULSES = 178;
    private static final long[][] PULSE_COUNTS = buildPulseCounts();
    private static final int ROUNDING = 16384;
    private static final int FIXED_POINT_SHIFT = 15;
    private static final int SEARCH_STEPS = 6;

    // U(n, k) of RFC 6716, which is symmetric, kept for n <= k only: entry [n][k]. It wraps for the sizes that no
    // band reaches.
    private static long[][] buildPulseCounts() {
        final var counts = new long[MAX_DIMENSIONS + 1][MAX_PULSES + 1];
        counts[0][0] = 1;
        for (var dimensions = 1; dimensions <= MAX_DIMENSIONS; dimensions++) {
            for (var pulses = dimensions; pulses <= MAX_PULSES; pulses++) {
                var sameDimensions = counts[dimensions - 1][dimensions];
                if (pulses > dimensions) {
                    sameDimensions = counts[dimensions][pulses - 1];
                }
                counts[dimensions][pulses] =
                        counts[dimensions - 1][pulses] + sameDimensions + counts[dimensions - 1][pulses - 1];
            }
        }
        return counts;
    }

    /**
     * @param dimensions the number of dimensions
     * @param pulses the number of pulses
     * @return U(dimensions, pulses) of RFC 6716
     */
    static long pulseCount(final int dimensions, final int pulses) {
        return PULSE_COUNTS[Math.min(dimensions, pulses)][Math.max(dimensions, pulses)];
    }

    /**
     * @param dimensions the number of dimensions
     * @param pulses the number of pulses
     * @return V(dimensions, pulses): how many vectors of that many dimensions have exactly that many pulses
     */
    static long vectorCount(final int dimensions, final int pulses) {
        return pulseCount(dimensions, pulses) + pulseCount(dimensions, pulses + 1);
    }

    /**
     * Finds the vector that an index names among the vectors of a number of dimensions with a number of pulses
     * (RFC 6716 section 4.3.4.2).
     *
     * @param dimensions how many dimensions, from 2
     * @param pulses how many pulses in all
     * @param index which vector, below {@link #vectorCount}
     * @param vector where the vector's pulses go, one per dimension, signed
     * @return the sum of the squares of the vector's entries
     */
    static long decodeVector(final int dimensions, final int pulses, final long index, final int[] vector) {
        var remainingIndex = index;
        var remainingPulses = pulses;
        var norm = 0L;
        var position = 0;
        for (var left = dimensions; left > 2; left--) {
            final var entry = decodeEntry(left, remainingPulses, remainingIndex);
            remainingIndex = entry.remainingIndex();
            remainingPulses = entry.remainingPulses();
            vector[position] = entry.value();
            norm += (long) entry.value() * entry.value();
            position++;
        }
        return norm + decodeLastTwo(remainingPulses, remainingIndex, vector, position);
    }

    private static PulseEntry decodeEntry(final int left, final int pulses, final long index) {
        if (pulses >= left) {
            return decodeEntryWithManyPulses(left, pulses, index);
        }
        return decodeEntryWithFewPulses(left, pulses, index);
    }

    private static PulseEntry decodeEntryWithManyPulses(final int left, final int pulses, final long index) {
        var remaining = index;
        final var negativeStart = pulseCount(left, pulses + 1);
        var sign = 1;
        if (remaining >= negativeStart) {
            sign = -1;
            remaining -= negativeStart;
        }
        var kept = pulses;
        if (pulseCount(left, left) > remaining) {
            kept = left;
            do {
                kept--;
            } while (pulseCount(kept, left) > remaining);
        } else {
            while (pulseCount(left, kept) > remaining) {
                kept--;
            }
        }
        remaining -= pulseCount(left, kept);
        return new PulseEntry(remaining, kept, sign * (pulses - kept));
    }

    private static PulseEntry decodeEntryWithFewPulses(final int left, final int pulses, final long index) {
        final var low = pulseCount(pulses, left);
        final var high = pulseCount(pulses + 1, left);
        if (low <= index && index < high) {
            return new PulseEntry(index - low, pulses, 0);
        }
        var remaining = index;
        var sign = 1;
        if (remaining >= high) {
            sign = -1;
            remaining -= high;
        }
        var kept = pulses;
        do {
            kept--;
        } while (pulseCount(kept, left) > remaining);
        remaining -= pulseCount(kept, left);
        return new PulseEntry(remaining, kept, sign * (pulses - kept));
    }

    // The last two dimensions are decoded without tables.
    private static long decodeLastTwo(final int pulses, final long index, final int[] vector, final int position) {
        final var negativeStart = 2L * pulses + 1;
        var remaining = index;
        var sign = 1;
        if (remaining >= negativeStart) {
            sign = -1;
            remaining -= negativeStart;
        }
        final var kept = (int) ((remaining + 1) / 2);
        if (kept != 0) {
            remaining -= 2L * kept - 1;
        }
        final var first = sign * (pulses - kept);
        var second = kept;
        if (remaining != 0) {
            second = -kept;
        }
        vector[position] = first;
        vector[position + 1] = second;
        return (long) first * first + (long) second * second;
    }

    /**
     * What decoding one dimension of a vector leaves: the index and the pulses still to place, and the entry.
     */
    private record PulseEntry(long remainingIndex, int remainingPulses, int value) {}

    /**
     * Finds how many pulses a band can have for a number of bits, as the closest of the counts the cache lists
     * (RFC 6716 section 4.3.3).
     *
     * @param cacheStart where the band's cache starts in {@link CeltCacheTables#BITS}
     * @param bits how many eighths of a bit the band has
     * @return the pulse count
     */
    static int pulsesForBits(final int cacheStart, final int bits) {
        final var cache = CeltCacheTables.BITS;
        var low = 0;
        var high = cache[cacheStart];
        final var target = bits - 1;
        for (var step = 0; step < SEARCH_STEPS; step++) {
            final var center = (low + high + 1) >> 1;
            if (cache[cacheStart + center] >= target) {
                high = center;
            } else {
                low = center;
            }
        }
        var lowCost = -1;
        if (low != 0) {
            lowCost = cache[cacheStart + low];
        }
        if (target - lowCost <= cache[cacheStart + high] - target) {
            return low;
        }
        return high;
    }

    /**
     * @param cacheStart where the band's cache starts in {@link CeltCacheTables#BITS}
     * @param pulses how many pulses
     * @return how many eighths of a bit coding that many pulses takes
     */
    static int bitsForPulses(final int cacheStart, final int pulses) {
        if (pulses == 0) {
            return 0;
        }
        return CeltCacheTables.BITS[cacheStart + pulses] + 1;
    }

    /**
     * @param pulseIndex the pulse count a band is coded with: the count itself up to 7, then 8 to 15 scaled up
     * @return the number of pulses it stands for
     */
    static int pulsesOfIndex(final int pulseIndex) {
        if (pulseIndex < 8) {
            return pulseIndex;
        }
        return (8 + (pulseIndex & 7)) << ((pulseIndex >> 3) - 1);
    }

    /**
     * @param angle an angle in 0 to 16384, a quarter turn
     * @return its cosine plus 1 as a Q15 value, by the polynomial of RFC 6716
     */
    static int fixedPointCosine(final int angle) {
        final var squared = (angle * angle + 4096) >> 13;
        final var inner = -7651 + roundedProduct(squared, 8277 + roundedProduct(-626, squared));
        final var cosine = (32767 - squared) + roundedProduct(squared, inner);
        // FFmpeg keeps both the polynomial and its result in 16 bits.
        return (short) ((short) cosine + 1);
    }

    private static int roundedProduct(final int first, final int second) {
        return (first * second + ROUNDING) >> FIXED_POINT_SHIFT;
    }

    /**
     * @param sine the sine of an angle, in Q15
     * @param cosine the cosine of it, in Q15
     * @return the base 2 logarithm of the tangent in Q11, by the polynomial of RFC 6716
     */
    static int logTangent(final int sine, final int cosine) {
        final var cosineBits = bitLength(cosine);
        final var sineBits = bitLength(sine);
        final var scaledCosine = cosine << (15 - cosineBits);
        final var scaledSine = sine << (15 - sineBits);
        return (sineBits << 11) - (cosineBits << 11) + logPolynomial(scaledSine) - logPolynomial(scaledCosine);
    }

    private static int logPolynomial(final int value) {
        return roundedProduct(value, roundedProduct(value, -2597) + 7932);
    }

    private static int bitLength(final int value) {
        return Integer.SIZE - Integer.numberOfLeadingZeros(value);
    }
}
