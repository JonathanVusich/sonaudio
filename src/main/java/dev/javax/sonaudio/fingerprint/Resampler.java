package dev.javax.sonaudio.fingerprint;

import static dev.javax.sonaudio.internal.ResamplerUtils.extendedSample;

import dev.javax.sonaudio.internal.ResamplerCursor;
import dev.javax.sonaudio.internal.ResamplerFilter;
import java.util.Arrays;

/**
 * Resamples one channel of floating-point audio the way FFmpeg's libswresample does as {@code fpcalc} configures it
 * (16 filter taps for the lower rate, 256 phases, linear interpolation between them, a cutoff at 0.8 of the lower
 * rate's Nyquist frequency and a Kaiser window with beta 9), with the taps summed in doubles. The filter has as many
 * phases as the two rates need to be exact, up to 256. The samples before the start of the audio are its mirror image,
 * and the end, once it's known, is reflected too.
 */
final class Resampler {

    private static final int PHASE_SHIFT = 8;
    private static final double CUTOFF = 0.8;

    private final int inputRate;
    private final int outputRate;
    private final int filterLength;
    private final int filterStride;
    private final ResamplerFilter filter;

    private enum End {
        OPEN,
        ENDED
    }

    public Resampler(final int inputRate, final int outputRate) {
        this.inputRate = inputRate;
        this.outputRate = outputRate;
        this.filter = new ResamplerFilter(inputRate, outputRate, CUTOFF, PHASE_SHIFT);
        this.filterLength = filter.filterLength();
        this.filterStride = filter.filterStride();
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
        final var cursor = new ResamplerCursor(-(filterLength - 1) / 2);
        var reflectionPending = end == End.ENDED;
        while (true) {
            if (cursor.position() + filterLength > available) {
                if (!reflectionPending) {
                    break;
                }
                available += reflectionLength(length - cursor.position());
                reflectionPending = false;
                continue;
            }
            output[count++] = sampleAt(input, length, window, cursor);
            cursor.advance(filter);
        }
        return Arrays.copyOf(output, count);
    }

    // The output sample for a filter starting at a position in the audio. A filter that reaches past either end is
    // given a copy of its samples with the mirror image of the start and the reflection of the end in place.
    private float sampleAt(final float[] input, final int length, final float[] window, final ResamplerCursor cursor) {
        final var start = cursor.position();
        if (start >= 0 && start + filterStride <= length) {
            return filter.outputSample(input, start, cursor.phase(), cursor.fraction());
        }
        for (var i = 0; i < filterStride; i++) {
            window[i] = extendedSample(input, length, start + i);
        }
        return filter.outputSample(window, 0, cursor.phase(), cursor.fraction());
    }

    // How many samples libswresample reflects off the end of the audio, from what it hasn't finished with.
    private int reflectionLength(final int leftover) {
        return (Math.min(leftover, filterLength) + 1) / 2;
    }
}
