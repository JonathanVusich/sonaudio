package dev.javax.sonaudio.internal;

import lombok.experimental.UtilityClass;

/**
 * The arithmetic libswresample builds its filters and reads its input with, which the fingerprint package's resampler
 * and the Opus decoder's share.
 */
@UtilityClass
public class ResamplerUtils {

    // FFmpeg's av_bessel_i0 for the arguments up to 15 a Kaiser window of beta 9 gives it: Blair and Edwards' minimax
    // rational approximation.
    private static final double[] BESSEL_NUMERATOR = {
        -2.2335582639474375249e+15,
        -5.5050369673018427753e+14,
        -3.2940087627407749166e+13,
        -8.4925101247114157499e+11,
        -1.1912746104985237192e+10,
        -1.0313066708737980747e+08,
        -5.9545626019847898221e+05,
        -2.4125195876041896775e+03,
        -7.0935347449210549190e+00,
        -1.5453977791786851041e-02,
        -2.5172644670688975051e-05,
        -3.0517226450451067446e-08,
        -2.6843448573468483278e-11,
        -1.5982226675653184646e-14,
        -5.2487866627945699800e-18,
    };
    private static final double[] BESSEL_DENOMINATOR = {
        -2.2335582639474375245e+15,
        7.8858692566751002988e+12,
        -1.2207067397808979846e+10,
        1.0377081058062166144e+07,
        -4.8527560179962773045e+03,
        1.0,
    };

    /**
     * @return the sample at the position, where the samples before the start are its mirror image and those after the
     *     end its reflection, or 0 past those
     */
    public static float extendedSample(final float[] input, final int length, final int position) {
        var source = position;
        if (position < 0) {
            source = -position;
        } else if (position >= length) {
            source = 2 * length - 1 - position;
        }
        if (source < 0 || source >= length) {
            return 0;
        }
        return input[source];
    }

    /**
     * @param sign the sine of the tap, which libswresample reads off the first tap's when the filter doesn't narrow the
     *     band
     * @return sin(x) / x, which is 1 at 0
     */
    public static double sinc(final double x, final double sign, final double factor) {
        if (x == 0) {
            return 1.0;
        }
        if (factor == 1.0) {
            return sign / x;
        }
        return StrictMath.sin(x) / x;
    }

    /**
     * @return as many phases as the rates make exact, if that's under the most asked for
     */
    public static int exactPhaseCount(final int inputRate, final int outputRate, final int maxPhases) {
        final var exact = outputRate / greatestCommonDivisor(outputRate, inputRate);
        if (exact <= maxPhases) {
            return (int) exact;
        }
        return maxPhases;
    }

    public static long greatestCommonDivisor(final long first, final long second) {
        var larger = first;
        var smaller = second;
        while (smaller != 0) {
            final var remainder = larger % smaller;
            larger = smaller;
            smaller = remainder;
        }
        return larger;
    }

    /**
     * @return the modified Bessel function of the first kind and order 0, for arguments up to 15
     */
    public static double besselI0(final double x) {
        if (x == 0) {
            return 1.0;
        }
        final var y = Math.abs(x) * Math.abs(x);
        return evaluatePolynomial(BESSEL_NUMERATOR, y) / evaluatePolynomial(BESSEL_DENOMINATOR, y);
    }

    /**
     * @param coefficients from the constant term up
     */
    public static double evaluatePolynomial(final double[] coefficients, final double x) {
        var sum = coefficients[coefficients.length - 1];
        for (var i = coefficients.length - 2; i >= 0; i--) {
            sum *= x;
            sum += coefficients[i];
        }
        return sum;
    }
}
