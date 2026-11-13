package dev.javax.sonaudio.internal;

import static dev.javax.sonaudio.internal.ResamplerUtils.besselI0;
import static dev.javax.sonaudio.internal.ResamplerUtils.exactPhaseCount;
import static dev.javax.sonaudio.internal.ResamplerUtils.greatestCommonDivisor;
import static dev.javax.sonaudio.internal.ResamplerUtils.sinc;

/**
 * The polyphase filter bank that libswresample builds to change a sample rate, with 16 taps for the lower rate and a
 * Kaiser window of beta 9, and the way it steps through the bank from one output sample to the next. What differs
 * between the programs that configure it is the cutoff and the number of phases: {@code fpcalc} uses 0.8 and 256, and
 * FFmpeg's Opus decoder the defaults, 0.97 and 1024. The filter has as many phases as the two rates need to be exact,
 * up to that. The taps are summed in doubles.
 */
public final class ResamplerFilter {

    private static final int FILTER_SIZE = 16;
    private static final double KAISER_BETA = 9;
    private static final int FILTER_ALIGNMENT = 8;
    // libswresample doubles its increments until one of them reaches this.
    private static final int INCREMENT_LIMIT = 1 << 20;

    private final int filterLength;
    private final int phaseCount;
    private final int filterStride;
    private final float[] filterBank;
    private final int sourceIncrement;
    private final int incrementQuotient;
    private final int incrementRemainder;

    /**
     * @param inputRate the sample rate of the audio to resample
     * @param outputRate the sample rate to resample it to
     * @param cutoff the filter's cutoff, as a fraction of the lower rate's Nyquist frequency
     * @param phaseShift the base 2 logarithm of the most phases the filter has
     */
    public ResamplerFilter(final int inputRate, final int outputRate, final double cutoff, final int phaseShift) {
        final var factor = Math.min(outputRate * cutoff / inputRate, 1.0);
        this.filterLength = evenLength(Math.max((int) Math.ceil(FILTER_SIZE / factor), 1));
        this.phaseCount = exactPhaseCount(inputRate, outputRate, 1 << phaseShift);
        this.filterStride = (filterLength + FILTER_ALIGNMENT - 1) / FILTER_ALIGNMENT * FILTER_ALIGNMENT;
        this.filterBank = filterBank(factor);
        final var divisor = greatestCommonDivisor(outputRate, (long) inputRate * phaseCount);
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
     * @return how many input samples each output sample is made of
     */
    public int filterLength() {
        return filterLength;
    }

    /**
     * @return how many samples a filter takes up in the bank, which is its length rounded up to a multiple of 8
     */
    public int filterStride() {
        return filterStride;
    }

    /**
     * @return how many phases the filter has, which is how many output samples a whole input sample is divided into
     */
    public int phaseCount() {
        return phaseCount;
    }

    /**
     * @return what the fraction of an output sample's position counts up to before it carries into the phase
     */
    public int sourceIncrement() {
        return sourceIncrement;
    }

    /**
     * @return how many phases the position moves on from one output sample to the next, before the fraction's carry
     */
    public int incrementQuotient() {
        return incrementQuotient;
    }

    /**
     * @return how much the fraction of the position grows from one output sample to the next
     */
    public int incrementRemainder() {
        return incrementRemainder;
    }

    /**
     * @param samples the input
     * @param offset the index of the first of the {@link #filterStride} samples the filter reads, of which only the
     *     first {@link #filterLength} are weighted unless the output interpolates between two phases
     * @param index the phase of the filter
     * @param fraction how far from the phase to the next the output falls, out of {@link #sourceIncrement}
     * @return the output sample: the filter for the phase applied to the samples from offset on
     */
    public float outputSample(final float[] samples, final int offset, final int index, final int fraction) {
        final var row = filterStride * index;
        final var sum = filteredSum(samples, offset, row, filterLength);
        if (incrementRemainder != 0 || fraction != 0) {
            return interpolatedSample(samples, offset, row, fraction, sum);
        }
        return (float) sum;
    }

    // The sums of this phase's filter and the next one's, blended by how far between the two the output falls.
    private float interpolatedSample(
            final float[] samples, final int offset, final int row, final int fraction, final double sum) {
        final var next = filteredSum(samples, offset, row + filterStride, filterStride);
        final var weight = (double) fraction / sourceIncrement;
        return (float) (sum + (next - sum) * weight);
    }

    // The filter's taps applied to the samples from offset on. The next phase's filter is summed to the end of its
    // padding, as the last phase's shifted copy of the first has a tap there.
    private double filteredSum(final float[] samples, final int offset, final int row, final int tapCount) {
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

    private static int evenLength(final int length) {
        if (length > 1) {
            return (length + 1) / 2 * 2;
        }
        return length;
    }
}
