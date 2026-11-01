package dev.javax.sonaudio.fingerprint;

import java.util.Arrays;

/**
 * Resamples one channel of floating-point audio the way FFmpeg's libswresample does as {@code fpcalc} configures it
 * (16 filter taps for the lower rate, 256 phases, linear interpolation between them, a cutoff at 0.8 of the lower
 * rate's Nyquist frequency and a Kaiser window with beta 9), with the taps summed in doubles. The filter has as many
 * phases as the two rates need to be exact, up to 256. The samples before the start of the audio are its mirror image,
 * and the end, once it's known, is reflected too.
 */
public final class Resampler {

    private static final int FILTER_SIZE = 16;
    private static final int PHASE_SHIFT = 8;
    private static final double CUTOFF = 0.8;
    private static final double KAISER_BETA = 9;
    private static final int FILTER_ALIGNMENT = 8;
    // libswresample doubles its increments until one of them reaches this.
    private static final int INCREMENT_LIMIT = 1 << 20;

    private final int inputRate;
    private final int outputRate;
    private final int filterLength;
    private final int phaseCount;
    private final int filterStride;
    private final float[] filterBank;
    private final int sourceIncrement;
    private final int incrementQuotient;
    private final int incrementRemainder;

    private enum End {
        OPEN,
        ENDED
    }

    public Resampler(final int inputRate, final int outputRate) {
        this.inputRate = inputRate;
        this.outputRate = outputRate;
        final var factor = Math.min(outputRate * CUTOFF / inputRate, 1.0);
        this.filterLength = evenLength(Math.max((int) Math.ceil(FILTER_SIZE / factor), 1));
        this.phaseCount = exactPhaseCount(inputRate, outputRate);
        this.filterStride = (filterLength + FILTER_ALIGNMENT - 1) / FILTER_ALIGNMENT * FILTER_ALIGNMENT;
        this.filterBank = filterBank(factor);
        final var divisor = gcd(outputRate, (long) inputRate * phaseCount);
        var source = (int) (outputRate / divisor);
        var destination = (int) (inputRate * (long) phaseCount / divisor);
        while (destination < INCREMENT_LIMIT && source < INCREMENT_LIMIT) {
            destination *= 2;
            source *= 2;
        }
        this.sourceIncrement = source;
        this.incrementQuotient = destination / source;
        this.incrementRemainder = destination % source;
    }

    /**
     * @return how many input samples are enough to make {@code outputCount} output samples
     */
    public int inputNeeded(final int outputCount) {
        return (int) ((long) outputCount * inputRate / outputRate) + filterLength + 2;
    }

    /**
     * @param length how many samples of {@code input} there are, with more audio to follow
     * @return the output samples whose filters lie within the input
     */
    public float[] resampleOpen(final float[] input, final int length) {
        return resample(input, length, End.OPEN);
    }

    /**
     * @param length how many samples of {@code input} there are, which are all the audio
     * @return the output samples, the last of them made from the input's reflection
     */
    public float[] resampleEnded(final float[] input, final int length) {
        return resample(input, length, End.ENDED);
    }

    private float[] resample(final float[] input, final int length, final End end) {
        final var output = new float[(int) ((long) (length + filterLength) * outputRate / inputRate) + 2];
        final var window = new float[filterStride];
        var available = length;
        var count = 0;
        var index = 0;
        var fraction = 0;
        var start = -(filterLength - 1) / 2;
        var reflectionPending = end == End.ENDED;
        while (true) {
            if (start + filterLength > available) {
                if (!reflectionPending) {
                    break;
                }
                available += reflectionLength(length - start);
                reflectionPending = false;
                continue;
            }
            output[count++] = sampleAt(input, length, window, start, index, fraction);
            fraction += incrementRemainder;
            index += incrementQuotient;
            if (fraction >= sourceIncrement) {
                fraction -= sourceIncrement;
                index++;
            }
            start += index / phaseCount;
            index %= phaseCount;
        }
        return Arrays.copyOf(output, count);
    }

    // The output sample for a filter starting at a position in the audio. A filter that reaches past either end is
    // given a copy of its samples with the mirror image of the start and the reflection of the end in place.
    private float sampleAt(
            final float[] input,
            final int length,
            final float[] window,
            final int start,
            final int index,
            final int fraction) {
        if (start >= 0 && start + filterStride <= length) {
            return outputSample(input, start, index, fraction);
        }
        for (var i = 0; i < filterStride; i++) {
            window[i] = extended(input, length, start + i);
        }
        return outputSample(window, 0, index, fraction);
    }

    private static float extended(final float[] input, final int length, final int position) {
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

    // How many samples libswresample reflects off the end of the audio, from what it hasn't finished with.
    private int reflectionLength(final int leftover) {
        return (Math.min(leftover, filterLength) + 1) / 2;
    }

    // One output sample: the filter for this phase applied to the samples from offset on.
    private float outputSample(final float[] samples, final int offset, final int index, final int fraction) {
        final var row = filterStride * index;
        final var sum = filtered(samples, offset, row, filterLength);
        if (incrementRemainder != 0 || fraction != 0) {
            return interpolatedSample(samples, offset, row, fraction, sum);
        }
        return (float) sum;
    }

    // The sums of this phase's filter and the next one's, blended by how far between the two the output falls.
    private float interpolatedSample(
            final float[] samples, final int offset, final int row, final int fraction, final double sum) {
        final var next = filtered(samples, offset, row + filterStride, filterStride);
        final var weight = (double) fraction / sourceIncrement;
        return (float) (sum + (next - sum) * weight);
    }

    // The filter's taps applied to the samples from offset on. The next phase's filter is summed to the end of its
    // padding, as the last phase's shifted copy of the first has a tap there.
    private double filtered(final float[] samples, final int offset, final int row, final int tapCount) {
        var sum = 0.0;
        for (var i = 0; i < tapCount; i++) {
            sum += (double) samples[offset + i] * filterBank[row + i];
        }
        return sum;
    }

    private float[] filterBank(final double factor) {
        final var bank = new float[filterStride * (phaseCount + 1)];
        final var phasesComputed = phasesComputed();
        final var center = (filterLength - 1) / 2;
        final var taps = new double[filterLength];
        final var norm = filterNorm(factor, center, taps);
        for (var phase = 0; phase < phasesComputed; phase++) {
            fillTaps(taps, phase, factor, center);
            for (var i = 0; i < filterLength; i++) {
                bank[phase * filterStride + i] = (float) (taps[i] / norm);
            }
            if (phaseCount % 2 == 0) {
                for (var i = 0; i < filterLength; i++) {
                    bank[(phaseCount - phase) * filterStride + filterLength - 1 - i] = bank[phase * filterStride + i];
                }
            }
        }
        // The phase after the last is the first one shifted by a sample, for the interpolation to blend towards.
        System.arraycopy(bank, 0, bank, filterStride * phaseCount + 1, filterStride - 1);
        bank[filterStride * phaseCount] = bank[filterStride - 1];
        return bank;
    }

    private int phasesComputed() {
        if (phaseCount % 2 != 0) {
            return phaseCount;
        }
        return phaseCount / 2 + 1;
    }

    // The sum of the first phase's taps, which every phase is divided by.
    private double filterNorm(final double factor, final int center, final double[] taps) {
        fillTaps(taps, 0, factor, center);
        var norm = 0.0;
        for (final var tap : taps) {
            norm += tap;
        }
        return norm;
    }

    // The windowed sinc for one phase, before it's normalised.
    private void fillTaps(final double[] taps, final int phase, final double factor, final int center) {
        var sign = initialSign(phase, factor, center);
        for (var i = 0; i < filterLength; i++) {
            final var x = Math.PI * ((double) (i - center) - (double) phase / phaseCount) * factor;
            var y = sinc(x, sign, factor);
            final var w = 2.0 * x / (factor * filterLength * Math.PI);
            y *= besselI0(KAISER_BETA * Math.sqrt(Math.max(1 - w * w, 0)));
            taps[i] = y;
            sign = -sign;
        }
    }

    // When the filter doesn't narrow the band, libswresample reads the sine of every tap off the first one's.
    private double initialSign(final int phase, final double factor, final int center) {
        if (factor != 1.0) {
            return 0;
        }
        var parity = -1;
        if ((center & 1) != 0) {
            parity = 1;
        }
        return StrictMath.sin(Math.PI * phase / phaseCount) * parity;
    }

    private static double sinc(final double x, final double sign, final double factor) {
        if (x == 0) {
            return 1.0;
        }
        if (factor == 1.0) {
            return sign / x;
        }
        return StrictMath.sin(x) / x;
    }

    // As many phases as the rates make exact, if that's under the 256 asked for.
    private static int exactPhaseCount(final int inputRate, final int outputRate) {
        final var exact = outputRate / gcd(outputRate, inputRate);
        if (exact <= 1 << PHASE_SHIFT) {
            return (int) exact;
        }
        return 1 << PHASE_SHIFT;
    }

    private static int evenLength(final int length) {
        if (length > 1) {
            return (length + 1) / 2 * 2;
        }
        return length;
    }

    private static long gcd(final long a, final long b) {
        var larger = a;
        var smaller = b;
        while (smaller != 0) {
            final var remainder = larger % smaller;
            larger = smaller;
            smaller = remainder;
        }
        return larger;
    }

    // FFmpeg's av_bessel_i0 for the arguments up to 15 a Kaiser window of beta 9 gives it: Blair and Edwards' minimax
    // rational approximation.
    private static double besselI0(final double x) {
        if (x == 0) {
            return 1.0;
        }
        final var y = Math.abs(x) * Math.abs(x);
        return evaluate(BESSEL_NUMERATOR, y) / evaluate(BESSEL_DENOMINATOR, y);
    }

    private static double evaluate(final double[] coefficients, final double x) {
        var sum = coefficients[coefficients.length - 1];
        for (var i = coefficients.length - 2; i >= 0; i--) {
            sum *= x;
            sum += coefficients[i];
        }
        return sum;
    }

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
}
