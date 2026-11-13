package dev.javax.sonaudio.format.opus;

import lombok.experimental.UtilityClass;

/**
 * The integer arithmetic that turns SILK's normalised line spectral frequencies into the coefficients of its linear
 * prediction filter (RFC 6716 sections 4.2.7.5.4 to 4.2.7.5.8), as FFmpeg's {@code silk.c} does it. It follows
 * FFmpeg and not the RFC where the two differ: the search for the largest coefficient, for one, only ever looks at the
 * first.
 */
@UtilityClass
final class SilkLpcUtils {

    /**
     * The most coefficients a filter has: wideband's.
     */
    static final int MAX_ORDER = 16;

    private static final int NLSF_ONE = 32768;
    private static final int STABILISATION_PASSES = 20;
    private static final int RANGE_LIMITING_PASSES = 10;
    private static final int STABILITY_PASSES = 16;
    private static final int MAX_Q12 = 32767;
    private static final int CHIRP_NUMERATOR_LIMIT = 163838;
    private static final int CHIRP_BASE = 65470;
    private static final int DC_RESPONSE_LIMIT = 4096;
    private static final int COEFFICIENT_LIMIT_Q24 = 16773022;
    private static final int MIN_INVERSE_GAIN = 107374;
    private static final int COSINE_STEP_BITS = 8;
    private static final int NARROWBAND_ORDER = 10;
    private static final float Q12_ONE = 4096.0f;

    /**
     * Pushes line spectral frequencies apart until each is at least its minimum spacing from the next, by moving the
     * offending pair a little at a time, and by sorting and pushing them if that takes more than 20 passes.
     *
     * @param nlsf the frequencies, in Q15, whose first {@code order} entries are used
     * @param order how many frequencies there are
     * @param minimumSpacing the least distance from each frequency to the one before it, and for the last entry
     *     from the last frequency to 1, {@code order + 1} entries
     * @return the frequencies, in a new array
     */
    static int[] stabilisedNlsf(final int[] nlsf, final int order, final int[] minimumSpacing) {
        final var stabilised = nlsf.clone();
        for (var pass = 0; pass < STABILISATION_PASSES; pass++) {
            final var worst = mostCrowdedGap(stabilised, order, minimumSpacing);
            if (worst < 0) {
                return stabilised;
            }
            spreadFromGap(stabilised, order, minimumSpacing, worst);
        }
        sortAndPush(stabilised, order, minimumSpacing);
        return stabilised;
    }

    // The gap that falls shortest of its minimum spacing, or -1 when none does. Gap i lies below frequency i.
    private static int mostCrowdedGap(final int[] nlsf, final int order, final int[] minimumSpacing) {
        var shortfall = 0;
        var worst = -1;
        for (var gap = 0; gap <= order; gap++) {
            var low = 0;
            if (gap != 0) {
                low = nlsf[gap - 1];
            }
            var high = NLSF_ONE;
            if (gap != order) {
                high = nlsf[gap];
            }
            final var difference = high - low - minimumSpacing[gap];
            if (difference < shortfall) {
                shortfall = difference;
                worst = gap;
            }
        }
        return worst;
    }

    private static void spreadFromGap(final int[] nlsf, final int order, final int[] minimumSpacing, final int gap) {
        if (gap == 0) {
            nlsf[0] = (short) minimumSpacing[0];
        } else if (gap == order) {
            nlsf[order - 1] = (short) (NLSF_ONE - minimumSpacing[order]);
        } else {
            spreadPair(nlsf, order, minimumSpacing, gap);
        }
    }

    // Moves the two frequencies either side of a gap apart around their centre, as far as the others allow.
    private static void spreadPair(final int[] nlsf, final int order, final int[] minimumSpacing, final int gap) {
        final var half = minimumSpacing[gap] >> 1;
        var lowestCentre = half;
        for (var index = 0; index < gap; index++) {
            lowestCentre += minimumSpacing[index];
        }
        var highestCentre = NLSF_ONE - half;
        for (var index = order; index > gap; index--) {
            highestCentre -= minimumSpacing[index];
        }
        final var sum = nlsf[gap - 1] + nlsf[gap];
        final var centre = Math.min(highestCentre, Math.max(lowestCentre, (sum >> 1) + (sum & 1)));
        nlsf[gap - 1] = (short) (centre - half);
        nlsf[gap] = (short) (nlsf[gap - 1] + minimumSpacing[gap]);
    }

    private static void sortAndPush(final int[] nlsf, final int order, final int[] minimumSpacing) {
        for (var index = 1; index < order; index++) {
            final var value = nlsf[index];
            var position = index - 1;
            while (position >= 0 && nlsf[position] > value) {
                nlsf[position + 1] = nlsf[position];
                position--;
            }
            nlsf[position + 1] = value;
        }
        if (nlsf[0] < minimumSpacing[0]) {
            nlsf[0] = (short) minimumSpacing[0];
        }
        for (var index = 1; index < order; index++) {
            nlsf[index] =
                    (short) Math.max(nlsf[index], Math.min(nlsf[index - 1] + minimumSpacing[index], NLSF_ONE - 1));
        }
        if (nlsf[order - 1] > NLSF_ONE - minimumSpacing[order]) {
            nlsf[order - 1] = (short) (NLSF_ONE - minimumSpacing[order]);
        }
        for (var index = order - 2; index >= 0; index--) {
            if (nlsf[index] > nlsf[index + 1] - minimumSpacing[index + 1]) {
                nlsf[index] = (short) (nlsf[index + 1] - minimumSpacing[index + 1]);
            }
        }
    }

    /**
     * Turns line spectral frequencies into filter coefficients: their cosines by table interpolation, the two
     * polynomials of the even and odd ones, and their sum and difference, the coefficients then limited to 16 bits and
     * their bandwidth expanded until the filter is stable.
     *
     * @param nlsf the frequencies, in Q15, whose first {@code order} entries are used
     * @param order 10 for narrowband and mediumband, 16 for wideband
     * @return the coefficients as floats (the Q12 values divided by 4096), in an array of {@link #MAX_ORDER} whose
     *     entries from {@code order} on are 0
     */
    static float[] nlsfToLpc(final int[] nlsf, final int order) {
        final var coefficientsQ17 = polynomialCoefficients(nlsf, order);
        final var limited = limitRange(coefficientsQ17, order);
        final var coefficientsQ12 = new int[MAX_ORDER];
        roundToQ12(coefficientsQ17, coefficientsQ12, order, limited);
        expandUntilStable(coefficientsQ17, coefficientsQ12, order);
        final var floats = new float[MAX_ORDER];
        for (var index = 0; index < order; index++) {
            floats[index] = coefficientsQ12[index] / Q12_ONE;
        }
        return floats;
    }

    // The filter coefficients in Q17, before they are limited: A(z) from the sum and difference of the two
    // polynomials whose roots are the line spectral frequencies.
    private static int[] polynomialCoefficients(final int[] nlsf, final int order) {
        final var cosines = new int[order];
        for (var index = 0; index < order; index++) {
            cosines[ordering(order, index)] = cosine(nlsf[index]);
        }
        final var even = lspToPolynomial(cosines, 0, order >> 1);
        final var odd = lspToPolynomial(cosines, 1, order >> 1);
        final var coefficients = new int[order];
        for (var index = 0; index < order >> 1; index++) {
            final var oddTerm = odd[index + 1] - odd[index];
            final var evenTerm = even[index + 1] + even[index];
            coefficients[index] = -oddTerm - evenTerm;
            coefficients[order - index - 1] = oddTerm - evenTerm;
        }
        return coefficients;
    }

    private static int ordering(final int order, final int index) {
        if (order == NARROWBAND_ORDER) {
            return SilkTables.LSF_ORDERING_NBMB[index];
        }
        return SilkTables.LSF_ORDERING_WB[index];
    }

    // 2 * cos(frequency) in Q17, by linear interpolation between the table's entries 1 / 128 of a half circle apart.
    private static int cosine(final int frequency) {
        final var index = frequency >> COSINE_STEP_BITS;
        final var offset = frequency & 255;
        final var base = SilkTables.COSINE[index];
        final var interpolated = base * 256 + (SilkTables.COSINE[index + 1] - base) * offset;
        return (interpolated + 4) >> 3;
    }

    // The polynomial of half the order whose roots are every other one of the cosines, from the first or the second.
    private static int[] lspToPolynomial(final int[] cosines, final int first, final int halfOrder) {
        final var polynomial = new int[halfOrder + 1];
        polynomial[0] = 65536;
        polynomial[1] = -cosines[first];
        for (var index = 1; index < halfOrder; index++) {
            final var cosine = cosines[first + 2 * index];
            polynomial[index + 1] =
                    (int) ((long) (polynomial[index - 1] * 2) - roundedProduct(cosine, polynomial[index], 16));
            for (var term = index; term > 1; term--) {
                polynomial[term] = (int) ((long) polynomial[term]
                        + ((long) polynomial[term - 2] - roundedProduct(cosine, polynomial[term - 1], 16)));
            }
            polynomial[1] -= cosine;
        }
        return polynomial;
    }

    // a * b >> shift, rounded to nearest.
    private static long roundedProduct(final long first, final long second, final int shift) {
        return (((first * second) >> (shift - 1)) + 1) >> 1;
    }

    private enum Limiting {
        FITTED,
        CLAMPED
    }

    // Shrinks the coefficients towards zero until the largest fits in 16 bits at Q12. FFmpeg looks for that largest
    // coefficient, but its search compares each against the first, so it is always the first that is measured.
    private static Limiting limitRange(final int[] coefficients, final int order) {
        for (var pass = 0; pass < RANGE_LIMITING_PASSES; pass++) {
            final var largest = (Math.abs(coefficients[0]) + 16) >>> 5;
            if (largest <= MAX_Q12) {
                return Limiting.FITTED;
            }
            expandRange(coefficients, order, Math.min(largest, CHIRP_NUMERATOR_LIMIT));
        }
        return Limiting.CLAMPED;
    }

    private static void expandRange(final int[] coefficients, final int order, final int largest) {
        // The search for the largest coefficient always finds the first, so the divisor is the first's alone.
        final var chirpBase = CHIRP_BASE - (((long) (largest - MAX_Q12) << 14) / ((long) largest >> 2));
        applyChirp(coefficients, order, chirpBase);
    }

    // Multiplies coefficient k by chirp^(k + 1), the chirp being a Q16 number a little under 1.
    private static void applyChirp(final int[] coefficients, final int order, final long chirpBase) {
        var chirp = chirpBase;
        for (var index = 0; index < order; index++) {
            coefficients[index] = (int) roundedProduct(coefficients[index], chirp, 16);
            chirp = (chirpBase * chirp + 32768) >> 16;
        }
    }

    private static void roundToQ12(
            final int[] coefficientsQ17, final int[] coefficientsQ12, final int order, final Limiting limiting) {
        for (var index = 0; index < order; index++) {
            final var rounded = (coefficientsQ17[index] + 16) >> 5;
            switch (limiting) {
                case FITTED -> coefficientsQ12[index] = (short) rounded;
                case CLAMPED -> {
                    final var clipped = (short) Math.clamp(rounded, Short.MIN_VALUE, Short.MAX_VALUE);
                    coefficientsQ12[index] = clipped;
                    coefficientsQ17[index] = clipped << 5;
                }
            }
        }
    }

    // Expands the bandwidth of a filter that the stability check rejects, by a growing amount, up to 16 times.
    private static void expandUntilStable(final int[] coefficientsQ17, final int[] coefficientsQ12, final int order) {
        for (var pass = 1; pass <= STABILITY_PASSES && !isStable(coefficientsQ12, order); pass++) {
            final var chirpBase = 65536 - (1L << pass);
            var chirp = chirpBase;
            for (var index = 0; index < order; index++) {
                coefficientsQ17[index] = (int) roundedProduct(coefficientsQ17[index], chirp, 16);
                coefficientsQ12[index] = (short) ((coefficientsQ17[index] + 16) >> 5);
                chirp = (chirpBase * chirp + 32768) >> 16;
            }
        }
    }

    /**
     * Whether a filter is stable, by the Levinson recursion run backwards on its Q12 coefficients in integer
     * arithmetic (RFC 8251 section 6 calls overflow of the recursion unstable): its DC response is under 1, no
     * reflection coefficient is near 1, and its prediction gain is not too large.
     *
     * @param coefficientsQ12 the coefficients, whose first {@code order} entries are used
     * @param order the number of coefficients
     * @return whether the filter is stable
     */
    static boolean isStable(final int[] coefficientsQ12, final int order) {
        var dcResponse = 0;
        final var rows = new int[2][MAX_ORDER];
        for (var index = 0; index < order; index++) {
            dcResponse += coefficientsQ12[index];
            rows[0][index] = coefficientsQ12[index] * 4096;
        }
        if (dcResponse >= DC_RESPONSE_LIMIT) {
            return false;
        }
        return recursionIsStable(rows, order);
    }

    private static boolean recursionIsStable(final int[][] rows, final int order) {
        var row = rows[0];
        var inverseGain = 1 << 30;
        for (var level = order - 1; ; level--) {
            if (Math.abs(row[level]) > COEFFICIENT_LIMIT_Q24) {
                return false;
            }
            final var reflection = -(row[level] * 128);
            final var divisor = (1 << 30) - highProduct(reflection, reflection);
            inverseGain = highProduct(inverseGain, divisor) << 2;
            if (level == 0) {
                return inverseGain >= MIN_INVERSE_GAIN;
            }
            final var next = rows[level & 1];
            if (!reduceRow(row, next, level, reflection, divisor)) {
                return false;
            }
            row = next;
        }
    }

    // Derives the row of the recursion below this one, scaling by an approximation of 1 / divisor, which fails when
    // a value overflows.
    private static boolean reduceRow(
            final int[] row, final int[] next, final int level, final int reflection, final int divisor) {
        final var fractionBits = 32 - Integer.numberOfLeadingZeros(divisor);
        var gain = ((1 << 29) - 1) / (divisor >> (fractionBits + 1 - 16));
        final var error = (int) ((1 << 29) - (((long) (divisor << (15 + 16 - fractionBits)) * gain) >> 16));
        gain = (gain << 16) + (error * gain >> 13);
        for (var index = 0; index < level; index++) {
            final var rounded = (int) roundedProduct(row[level - index - 1], reflection, 31);
            final var difference = Math.clamp((long) row[index] - rounded, Integer.MIN_VALUE, Integer.MAX_VALUE);
            final var scaled = roundedProduct(difference, gain, fractionBits);
            if (scaled < Integer.MIN_VALUE || scaled > Integer.MAX_VALUE) {
                return false;
            }
            next[index] = (int) scaled;
        }
        return true;
    }

    private static int highProduct(final int first, final int second) {
        return (int) (((long) first * second) >> 32);
    }
}
