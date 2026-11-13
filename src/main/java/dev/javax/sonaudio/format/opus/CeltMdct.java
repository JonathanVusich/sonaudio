package dev.javax.sonaudio.format.opus;

/**
 * The inverse MDCT of one size that CELT synthesises a block with (RFC 6716 section 4.3.7). It is the half of the
 * inverse transform that FFmpeg's {@code libavutil/tx} gives for {@code n} coefficients: {@code n} samples, which are
 * the {@code n}-point DCT-IV of the coefficients in reverse order, scaled by -1/32768. It is computed through an FFT
 * of {@code n / 2} points.
 *
 * <p>It is not FFmpeg's split-radix and prime-factor code, so its results differ from FFmpeg's at the level of float
 * rounding; the test checks the definition, in doubles.
 */
final class CeltMdct {

    // FFmpeg's scale for the transform, which also brings its coefficients to the -1 to 1 range of the samples.
    private static final float SCALE = -1.0f / 32768;

    private final int size;
    private final CeltFft fft;
    private final float[] preRotation;
    private final float[] postRotation;
    private final float[] folded;
    private final float[] transformed;

    /**
     * @param size how many coefficients the transform takes, which is how many samples it gives: even, and half a
     *     product of 2s, 3s and 5s
     */
    CeltMdct(final int size) {
        this.size = size;
        final var half = size / 2;
        this.fft = new CeltFft(half);
        this.preRotation = new float[size];
        this.postRotation = new float[size];
        this.folded = new float[size];
        this.transformed = new float[size];
        for (var index = 0; index < half; index++) {
            final var preAngle = -Math.PI * (4 * index + 1) / (4.0 * size);
            preRotation[2 * index] = (float) Math.cos(preAngle);
            preRotation[2 * index + 1] = (float) Math.sin(preAngle);
            final var postAngle = -Math.PI * index / size;
            postRotation[2 * index] = (float) (Math.cos(postAngle) * SCALE);
            postRotation[2 * index + 1] = (float) (Math.sin(postAngle) * SCALE);
        }
    }

    /**
     * Transforms a block of coefficients to samples.
     *
     * @param coefficients the array the coefficients are in
     * @param firstCoefficient the index of the first
     * @param coefficientStride the distance between one coefficient and the next, which is how many blocks a
     *     transient frame has, as their coefficients are interleaved
     * @param output where the samples go
     * @param firstSample the index of the first
     */
    void inverse(
            final float[] coefficients,
            final int firstCoefficient,
            final int coefficientStride,
            final float[] output,
            final int firstSample) {
        rotateInput(coefficients, firstCoefficient, coefficientStride);
        fft.transform(folded, transformed);
        rotateOutput(output, firstSample);
    }

    // Pairs the coefficients from the two ends (even ones from the front, odd ones from the back) as complex points.
    private void rotateInput(final float[] coefficients, final int firstCoefficient, final int coefficientStride) {
        final var half = size / 2;
        for (var index = 0; index < half; index++) {
            final var real = coefficients[firstCoefficient + 2 * index * coefficientStride];
            final var imaginary = coefficients[firstCoefficient + (size - 1 - 2 * index) * coefficientStride];
            final var cosine = preRotation[2 * index];
            final var sine = preRotation[2 * index + 1];
            folded[2 * index] = real * cosine - imaginary * sine;
            folded[2 * index + 1] = real * sine + imaginary * cosine;
        }
    }

    private void rotateOutput(final float[] output, final int firstSample) {
        final var half = size / 2;
        for (var index = 0; index < half; index++) {
            final var real = transformed[2 * index];
            final var imaginary = transformed[2 * index + 1];
            final var cosine = postRotation[2 * index];
            final var sine = postRotation[2 * index + 1];
            output[firstSample + size - 1 - 2 * index] = real * cosine - imaginary * sine;
            output[firstSample + 2 * index] = -(real * sine + imaginary * cosine);
        }
    }
}
