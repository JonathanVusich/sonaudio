package dev.javax.sonaudio.format.opus;

import static dev.javax.sonaudio.format.opus.SilkLpcUtils.MAX_ORDER;
import static dev.javax.sonaudio.format.opus.SilkLpcUtils.highProduct;
import static dev.javax.sonaudio.format.opus.SilkLpcUtils.roundedProduct;

/**
 * The integer arithmetic that turns SILK's normalised line spectral frequencies into the coefficients of its linear
 * prediction filter (RFC 6716 sections 4.2.7.5.4 to 4.2.7.5.8), as FFmpeg's {@code silk.c} does it. It follows
 * FFmpeg and not the RFC where the two differ: the search for the largest coefficient, for one, only ever looks at the
 * first.
 *
 * <p>The class keeps the working arrays of the arithmetic, which every call rewrites, so that converting a frame's
 * frequencies allocates only the filter it returns.
 */
final class SilkLpcConverter {

    private static final int NLSF_ONE = 32768;
    private static final int NLSF_MAX = NLSF_ONE - 1;
    private static final int STABILISATION_PASSES = 20;
    private static final int RANGE_LIMITING_PASSES = 10;
    private static final int STABILITY_PASSES = 16;
    private static final int MAX_Q12 = 32767;
    private static final int CHIRP_NUMERATOR_LIMIT = 163838;
    private static final int CHIRP_BASE = 65470;
    private static final int ONE_Q16 = 65536;
    private static final int CHIRP_ROUNDING = 32768;
    private static final int DC_RESPONSE_LIMIT = 4096;
    private static final int COEFFICIENT_LIMIT_Q24 = 16773022;
    private static final int MIN_INVERSE_GAIN = 107374;
    private static final int ONE_Q30 = 1 << 30;
    private static final int ONE_Q29 = 1 << 29;
    private static final float Q12_ONE = 4096.0f;
    private static final int ROUNDING_16 = 16;
    private static final int Q17_TO_Q12 = 5;

    private final int[] stabilised = new int[MAX_ORDER];
    private final int[] cosines = new int[MAX_ORDER];
    private final int[] evenPolynomial = new int[MAX_ORDER / 2 + 1];
    private final int[] oddPolynomial = new int[MAX_ORDER / 2 + 1];
    private final int[] coefficientsQ17 = new int[MAX_ORDER];
    private final int[] coefficientsQ12 = new int[MAX_ORDER];
    private final int[][] recursionRows = new int[2][MAX_ORDER];

    private enum Limiting {
        FITTED,
        CLAMPED
    }

    /**
     * Pushes line spectral frequencies apart until each is at least its minimum spacing from the next, by moving the
     * offending pair a little at a time, and by sorting and pushing them if that takes more than 20 passes.
     *
     * @param nlsf the frequencies, in Q15, whose first {@code order} entries are used
     * @param order how many frequencies there are
     * @param minimumSpacing the least distance from each frequency to the one before it, and for the last entry
     *     from the last frequency to 1, {@code order + 1} entries
     * @return the frequencies, in a buffer that the next call rewrites
     */
    int[] stabilisedNlsf(final int[] nlsf, final int order, final int[] minimumSpacing) {
        System.arraycopy(nlsf, 0, stabilised, 0, MAX_ORDER);
        for (var pass = 0; pass < STABILISATION_PASSES; pass++) {
            final var worst = mostCrowdedGap(order, minimumSpacing);
            if (worst < 0) {
                return stabilised;
            }
            spreadFromGap(order, minimumSpacing, worst);
        }
        sortFrequencies(order);
        pushForwards(order, minimumSpacing);
        pushBackwards(order, minimumSpacing);
        return stabilised;
    }

    // The gap that falls shortest of its minimum spacing, or -1 when none does. Gap i lies below frequency i.
    private int mostCrowdedGap(final int order, final int[] minimumSpacing) {
        var shortfall = 0;
        var worst = -1;
        for (var gap = 0; gap <= order; gap++) {
            final var difference = upperEdge(order, gap) - lowerEdge(gap) - minimumSpacing[gap];
            if (difference < shortfall) {
                shortfall = difference;
                worst = gap;
            }
        }
        return worst;
    }

    private int lowerEdge(final int gap) {
        if (gap == 0) {
            return 0;
        }
        return stabilised[gap - 1];
    }

    private int upperEdge(final int order, final int gap) {
        if (gap == order) {
            return NLSF_ONE;
        }
        return stabilised[gap];
    }

    private void spreadFromGap(final int order, final int[] minimumSpacing, final int gap) {
        if (gap == 0) {
            stabilised[0] = (short) minimumSpacing[0];
        } else if (gap == order) {
            stabilised[order - 1] = (short) (NLSF_ONE - minimumSpacing[order]);
        } else {
            spreadPair(order, minimumSpacing, gap);
        }
    }

    // Moves the two frequencies either side of a gap apart around their centre, as far as the others allow.
    private void spreadPair(final int order, final int[] minimumSpacing, final int gap) {
        final var half = minimumSpacing[gap] >> 1;
        var lowestCentre = half;
        for (var index = 0; index < gap; index++) {
            lowestCentre += minimumSpacing[index];
        }
        var highestCentre = NLSF_ONE - half;
        for (var index = order; index > gap; index--) {
            highestCentre -= minimumSpacing[index];
        }
        final var sum = stabilised[gap - 1] + stabilised[gap];
        final var centre = Math.min(highestCentre, Math.max(lowestCentre, (sum >> 1) + (sum & 1)));
        stabilised[gap - 1] = (short) (centre - half);
        stabilised[gap] = (short) (stabilised[gap - 1] + minimumSpacing[gap]);
    }

    // The fall-back after the passes: an insertion sort, as the frequencies are nearly sorted already.
    private void sortFrequencies(final int order) {
        for (var index = 1; index < order; index++) {
            final var value = stabilised[index];
            var position = index - 1;
            while (position >= 0 && stabilised[position] > value) {
                stabilised[position + 1] = stabilised[position];
                position--;
            }
            stabilised[position + 1] = value;
        }
    }

    private void pushForwards(final int order, final int[] minimumSpacing) {
        if (stabilised[0] < minimumSpacing[0]) {
            stabilised[0] = (short) minimumSpacing[0];
        }
        for (var index = 1; index < order; index++) {
            final var lowest = Math.min(stabilised[index - 1] + minimumSpacing[index], NLSF_MAX);
            stabilised[index] = (short) Math.max(stabilised[index], lowest);
        }
    }

    private void pushBackwards(final int order, final int[] minimumSpacing) {
        if (stabilised[order - 1] > NLSF_ONE - minimumSpacing[order]) {
            stabilised[order - 1] = (short) (NLSF_ONE - minimumSpacing[order]);
        }
        for (var index = order - 2; index >= 0; index--) {
            if (stabilised[index] > stabilised[index + 1] - minimumSpacing[index + 1]) {
                stabilised[index] = (short) (stabilised[index + 1] - minimumSpacing[index + 1]);
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
     * @return the coefficients as floats (the Q12 values divided by 4096), in an array of {@link
     *     SilkLpcUtils#MAX_ORDER} whose entries from {@code order} on are 0
     */
    float[] nlsfToLpc(final int[] nlsf, final int order) {
        polynomialCoefficients(nlsf, order);
        final var limiting = limitRange(order);
        roundToQ12(order, limiting);
        expandUntilStable(order);
        final var floats = new float[MAX_ORDER];
        for (var index = 0; index < order; index++) {
            floats[index] = coefficientsQ12[index] / Q12_ONE;
        }
        return floats;
    }

    // The filter coefficients in Q17, before they are limited: A(z) from the sum and difference of the two
    // polynomials whose roots are the line spectral frequencies.
    private void polynomialCoefficients(final int[] nlsf, final int order) {
        for (var index = 0; index < order; index++) {
            cosines[SilkLpcUtils.ordering(order, index)] = SilkLpcUtils.cosine(nlsf[index]);
        }
        fillPolynomial(evenPolynomial, 0, order >> 1);
        fillPolynomial(oddPolynomial, 1, order >> 1);
        for (var index = 0; index < order >> 1; index++) {
            final var oddTerm = oddPolynomial[index + 1] - oddPolynomial[index];
            final var evenTerm = evenPolynomial[index + 1] + evenPolynomial[index];
            coefficientsQ17[index] = -oddTerm - evenTerm;
            coefficientsQ17[order - index - 1] = oddTerm - evenTerm;
        }
    }

    // The polynomial of half the order whose roots are every other one of the cosines, from the first or the second.
    private void fillPolynomial(final int[] polynomial, final int first, final int halfOrder) {
        polynomial[0] = ONE_Q16;
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
    }

    // Shrinks the coefficients towards zero until the largest fits in 16 bits at Q12. FFmpeg looks for that largest
    // coefficient, but its search compares each against the first, so it is always the first that is measured.
    private Limiting limitRange(final int order) {
        for (var pass = 0; pass < RANGE_LIMITING_PASSES; pass++) {
            final var largest = (Math.abs(coefficientsQ17[0]) + ROUNDING_16) >>> Q17_TO_Q12;
            if (largest <= MAX_Q12) {
                return Limiting.FITTED;
            }
            // The search for the largest coefficient always finds the first, so the divisor is the first's alone.
            final var limited = Math.min(largest, CHIRP_NUMERATOR_LIMIT);
            applyChirp(order, CHIRP_BASE - (((long) (limited - MAX_Q12) << 14) / ((long) limited >> 2)));
        }
        return Limiting.CLAMPED;
    }

    // Multiplies coefficient k by chirp^(k + 1), the chirp being a Q16 number a little under 1.
    private void applyChirp(final int order, final long chirpBase) {
        var chirp = chirpBase;
        for (var index = 0; index < order; index++) {
            coefficientsQ17[index] = (int) roundedProduct(coefficientsQ17[index], chirp, 16);
            chirp = (chirpBase * chirp + CHIRP_ROUNDING) >> 16;
        }
    }

    private void roundToQ12(final int order, final Limiting limiting) {
        for (var index = 0; index < order; index++) {
            final var rounded = (coefficientsQ17[index] + ROUNDING_16) >> Q17_TO_Q12;
            switch (limiting) {
                case FITTED -> coefficientsQ12[index] = (short) rounded;
                case CLAMPED -> {
                    final var clipped = (short) Math.clamp(rounded, Short.MIN_VALUE, Short.MAX_VALUE);
                    coefficientsQ12[index] = clipped;
                    coefficientsQ17[index] = clipped << Q17_TO_Q12;
                }
            }
        }
    }

    // Expands the bandwidth of a filter that the stability check rejects, by a growing amount, up to 16 times.
    private void expandUntilStable(final int order) {
        for (var pass = 1; pass <= STABILITY_PASSES && !isStable(coefficientsQ12, order); pass++) {
            final var chirpBase = ONE_Q16 - (1L << pass);
            var chirp = chirpBase;
            for (var index = 0; index < order; index++) {
                coefficientsQ17[index] = (int) roundedProduct(coefficientsQ17[index], chirp, 16);
                coefficientsQ12[index] = (short) ((coefficientsQ17[index] + ROUNDING_16) >> Q17_TO_Q12);
                chirp = (chirpBase * chirp + CHIRP_ROUNDING) >> 16;
            }
        }
    }

    /**
     * Whether a filter is stable, by the Levinson recursion run backwards on its Q12 coefficients in integer
     * arithmetic (RFC 8251 section 6 calls overflow of the recursion unstable): its DC response is under 1, no
     * reflection coefficient is near 1, and its prediction gain is not too large.
     *
     * @param filterQ12 the coefficients, whose first {@code order} entries are used
     * @param order the number of coefficients
     * @return whether the filter is stable
     */
    boolean isStable(final int[] filterQ12, final int order) {
        var dcResponse = 0;
        for (var index = 0; index < order; index++) {
            dcResponse += filterQ12[index];
            recursionRows[0][index] = filterQ12[index] * 4096;
        }
        if (dcResponse >= DC_RESPONSE_LIMIT) {
            return false;
        }
        return recursionIsStable(order);
    }

    private boolean recursionIsStable(final int order) {
        var row = recursionRows[0];
        var inverseGain = ONE_Q30;
        for (var level = order - 1; ; level--) {
            if (Math.abs(row[level]) > COEFFICIENT_LIMIT_Q24) {
                return false;
            }
            final var reflection = -(row[level] * 128);
            final var divisor = ONE_Q30 - highProduct(reflection, reflection);
            inverseGain = highProduct(inverseGain, divisor) << 2;
            if (level == 0) {
                return inverseGain >= MIN_INVERSE_GAIN;
            }
            final var next = recursionRows[level & 1];
            if (!reduceRow(row, next, level, reflection, divisor)) {
                return false;
            }
            row = next;
        }
    }

    // Derives the row of the recursion below this one, scaling by an approximation of 1 / divisor, which fails when
    // a value overflows.
    private boolean reduceRow(
            final int[] row, final int[] next, final int level, final int reflection, final int divisor) {
        final var fractionBits = 32 - Integer.numberOfLeadingZeros(divisor);
        final var gain = refinedInverse(divisor, fractionBits);
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

    // 1 / divisor as a number of Q(fractionBits): a division of its top 16 bits, improved by one step of Newton's
    // method.
    private int refinedInverse(final int divisor, final int fractionBits) {
        final var gain = (ONE_Q29 - 1) / (divisor >> (fractionBits + 1 - 16));
        final var error = (int) (ONE_Q29 - (((long) (divisor << (15 + 16 - fractionBits)) * gain) >> 16));
        return (gain << 16) + (error * gain >> 13);
    }
}
