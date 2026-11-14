package dev.javax.sonaudio.format.opus;

import java.util.Arrays;

/**
 * A forward complex FFT of one size, whose length is a product of 2s, 3s and 5s, which is every size CELT's inverse
 * MDCT needs (60, 120, 240 and 480 points). It splits the transform by one prime factor at a time, so it needs no
 * scratch space beyond what it allocates when it is built.
 *
 * <p>Values are interleaved: the real part, then the imaginary part, of each point.
 */
final class CeltFft {

    private static final int[] RADICES = {5, 3, 2};
    private static final int MAX_RADIX = 5;

    private final int length;
    private final int[] factors;
    private final float[] twiddleReal;
    private final float[] twiddleImaginary;
    private final float[] gatheredReal = new float[MAX_RADIX];
    private final float[] gatheredImaginary = new float[MAX_RADIX];

    /**
     * @param length how many points, the product of 2s, 3s and 5s
     */
    CeltFft(final int length) {
        this.length = length;
        this.factors = factorize(length);
        this.twiddleReal = new float[length];
        this.twiddleImaginary = new float[length];
        for (var index = 0; index < length; index++) {
            final var angle = -2 * Math.PI * index / length;
            twiddleReal[index] = (float) Math.cos(angle);
            twiddleImaginary[index] = (float) Math.sin(angle);
        }
    }

    private int[] factorize(final int number) {
        var remaining = number;
        var count = 0;
        final var found = new int[Integer.SIZE];
        for (final var radix : RADICES) {
            while (remaining % radix == 0) {
                found[count] = radix;
                count++;
                remaining /= radix;
            }
        }
        return Arrays.copyOf(found, count);
    }

    /**
     * Transforms {@code length} points.
     *
     * @param input the points, interleaved, which are left as they are
     * @param output where the transformed points go, interleaved: not the same array as the input
     */
    void transform(final float[] input, final float[] output) {
        split(input, 0, 1, output, 0, length, 0);
    }

    private void split(
            final float[] input,
            final int inputStart,
            final int inputStride,
            final float[] output,
            final int outputStart,
            final int size,
            final int factorIndex) {
        if (size == 1) {
            output[2 * outputStart] = input[2 * inputStart];
            output[2 * outputStart + 1] = input[2 * inputStart + 1];
            return;
        }
        final var radix = factors[factorIndex];
        final var part = size / radix;
        for (var residue = 0; residue < radix; residue++) {
            final var partStart = inputStart + residue * inputStride;
            split(input, partStart, inputStride * radix, output, outputStart + residue * part, part, factorIndex + 1);
        }
        combine(output, outputStart, size, radix);
    }

    // Joins the radix transforms of the parts, each of size / radix points, into one of size points.
    private void combine(final float[] output, final int start, final int size, final int radix) {
        final var part = size / radix;
        final var sizeStep = length / size;
        final var radixStep = length / radix;
        for (var bin = 0; bin < part; bin++) {
            gather(output, start + bin, part, radix, sizeStep * bin);
            for (var target = 0; target < radix; target++) {
                spread(output, start + bin + target * part, radix, target, radixStep);
            }
        }
    }

    private void gather(final float[] output, final int first, final int part, final int radix, final int twiddleStep) {
        for (var residue = 0; residue < radix; residue++) {
            final var position = 2 * (first + residue * part);
            final var twiddle = residue * twiddleStep % length;
            final var real = output[position];
            final var imaginary = output[position + 1];
            gatheredReal[residue] = real * twiddleReal[twiddle] - imaginary * twiddleImaginary[twiddle];
            gatheredImaginary[residue] = real * twiddleImaginary[twiddle] + imaginary * twiddleReal[twiddle];
        }
    }

    private void spread(final float[] output, final int start, final int radix, final int target, final int radixStep) {
        var real = 0f;
        var imaginary = 0f;
        for (var residue = 0; residue < radix; residue++) {
            final var twiddle = (residue * target % radix) * radixStep;
            real += gatheredReal[residue] * twiddleReal[twiddle]
                    - gatheredImaginary[residue] * twiddleImaginary[twiddle];
            imaginary += gatheredReal[residue] * twiddleImaginary[twiddle]
                    + gatheredImaginary[residue] * twiddleReal[twiddle];
        }
        output[2 * start] = real;
        output[2 * start + 1] = imaginary;
    }
}
