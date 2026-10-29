package dev.javax.sonaudio.fingerprint;

/**
 * The real fast Fourier transform of frames of one size, a power of two, in single precision. It follows KissFFT's
 * {@code kiss_fftr}, which Chromaprint's {@code fpcalc} is built with, operation for operation (a complex transform
 * of half the size in radix-4 stages and a final radix-2 stage, then the split into the real transform's bins), so a
 * frame's powers come out bit for bit as Chromaprint's do. It reuses its working arrays from frame to frame, so one
 * transform isn't safe to share between threads.
 */
public final class Fft {

    private static final int RADIX_FOUR = 4;
    private static final int RADIX_TWO = 2;

    private final int size;
    // The complex transform's length, half of the frame's.
    private final int complexSize;
    private final float[] twiddleReal;
    private final float[] twiddleImaginary;
    private final float[] splitReal;
    private final float[] splitImaginary;
    // The radix and the length left after it for each stage, in the order KissFFT works through them.
    private final int[] radixes;
    private final int[] remainders;
    private final float[] outputReal;
    private final float[] outputImaginary;

    public Fft(final int size) {
        this.size = size;
        this.complexSize = size / 2;
        this.twiddleReal = new float[complexSize];
        this.twiddleImaginary = new float[complexSize];
        for (var i = 0; i < complexSize; i++) {
            final var phase = -2 * Math.PI * i / complexSize;
            twiddleReal[i] = (float) StrictMath.cos(phase);
            twiddleImaginary[i] = (float) StrictMath.sin(phase);
        }
        this.splitReal = new float[complexSize / 2];
        this.splitImaginary = new float[complexSize / 2];
        for (var i = 0; i < complexSize / 2; i++) {
            final var phase = -3.14159265358979323846264338327 * ((double) (i + 1) / complexSize + .5);
            splitReal[i] = (float) StrictMath.cos(phase);
            splitImaginary[i] = (float) StrictMath.sin(phase);
        }
        final var stages = stageCount(complexSize);
        this.radixes = new int[stages];
        this.remainders = new int[stages];
        var remaining = complexSize;
        for (var stage = 0; stage < stages; stage++) {
            final var radix = radixOfStage(remaining);
            remaining /= radix;
            radixes[stage] = radix;
            remainders[stage] = remaining;
        }
        this.outputReal = new float[complexSize];
        this.outputImaginary = new float[complexSize];
    }

    /**
     * @param frame {@code size} samples
     * @return the frame's power at each frequency from 0 up to half the sample rate
     */
    public PowerSpectrum powerSpectrum(final float[] frame) {
        work(0, 0, 1, 0, frame);
        final var powers = new double[size / 2 + 1];
        final var first = outputReal[0];
        final var firstImaginary = outputImaginary[0];
        final var dcReal = first + firstImaginary;
        final var nyquistReal = first - firstImaginary;
        powers[0] = dcReal * dcReal;
        powers[complexSize] = nyquistReal * nyquistReal;
        for (var k = 1; k <= complexSize / 2; k++) {
            splitBins(k, powers);
        }
        return new PowerSpectrum(powers);
    }

    // The real transform's bins k and complexSize - k from the complex transform's, as kiss_fftr does.
    private void splitBins(final int k, final double[] powers) {
        final var other = complexSize - k;
        final var pairReal = outputReal[k];
        final var pairImaginary = outputImaginary[k];
        final var mirrorReal = outputReal[other];
        final var mirrorImaginary = -outputImaginary[other];
        final var sumReal = pairReal + mirrorReal;
        final var sumImaginary = pairImaginary + mirrorImaginary;
        final var differenceReal = pairReal - mirrorReal;
        final var differenceImaginary = pairImaginary - mirrorImaginary;
        final var twiddleRealPart = splitReal[k - 1];
        final var twiddleImaginaryPart = splitImaginary[k - 1];
        final var rotatedReal = differenceReal * twiddleRealPart - differenceImaginary * twiddleImaginaryPart;
        final var rotatedImaginary = differenceReal * twiddleImaginaryPart + differenceImaginary * twiddleRealPart;
        final var lowReal = (float) ((sumReal + rotatedReal) * .5);
        final var lowImaginary = (float) ((sumImaginary + rotatedImaginary) * .5);
        final var highReal = (float) ((sumReal - rotatedReal) * .5);
        final var highImaginary = (float) ((rotatedImaginary - sumImaginary) * .5);
        powers[k] = lowReal * lowReal + lowImaginary * lowImaginary;
        powers[other] = highReal * highReal + highImaginary * highImaginary;
    }

    // KissFFT's kf_work: a transform of this stage's length from p transforms of the next, on decimated inputs.
    private void work(final int output, final int input, final int stride, final int stage, final float[] samples) {
        final var radix = radixes[stage];
        final var length = remainders[stage];
        final var end = output + radix * length;
        var out = output;
        var in = input;
        if (length == 1) {
            do {
                outputReal[out] = samples[2 * in];
                outputImaginary[out] = samples[2 * in + 1];
                in += stride;
            } while (++out != end);
        } else {
            do {
                work(out, in, stride * radix, stage + 1, samples);
                in += stride;
                out += length;
            } while (out != end);
        }
        switch (radix) {
            case RADIX_TWO -> butterflyTwo(output, stride, length);
            case RADIX_FOUR -> butterflyFour(output, stride, length);
            default -> throw new IllegalStateException("Unsupported radix " + radix);
        }
    }

    private void butterflyTwo(final int output, final int stride, final int length) {
        var low = output;
        var high = output + length;
        var twiddle = 0;
        for (var remaining = length; remaining > 0; remaining--) {
            final var twiddledReal =
                    outputReal[high] * twiddleReal[twiddle] - outputImaginary[high] * twiddleImaginary[twiddle];
            final var twiddledImaginary =
                    outputReal[high] * twiddleImaginary[twiddle] + outputImaginary[high] * twiddleReal[twiddle];
            twiddle += stride;
            outputReal[high] = outputReal[low] - twiddledReal;
            outputImaginary[high] = outputImaginary[low] - twiddledImaginary;
            outputReal[low] += twiddledReal;
            outputImaginary[low] += twiddledImaginary;
            high++;
            low++;
        }
    }

    private void butterflyFour(final int output, final int stride, final int length) {
        var first = output;
        var twiddleOne = 0;
        var twiddleTwo = 0;
        var twiddleThree = 0;
        for (var remaining = length; remaining > 0; remaining--) {
            final var second = first + length;
            final var third = second + length;
            final var fourth = third + length;
            final var oneReal = outputReal[second] * twiddleReal[twiddleOne]
                    - outputImaginary[second] * twiddleImaginary[twiddleOne];
            final var oneImaginary = outputReal[second] * twiddleImaginary[twiddleOne]
                    + outputImaginary[second] * twiddleReal[twiddleOne];
            final var twoReal =
                    outputReal[third] * twiddleReal[twiddleTwo] - outputImaginary[third] * twiddleImaginary[twiddleTwo];
            final var twoImaginary =
                    outputReal[third] * twiddleImaginary[twiddleTwo] + outputImaginary[third] * twiddleReal[twiddleTwo];
            final var threeReal = outputReal[fourth] * twiddleReal[twiddleThree]
                    - outputImaginary[fourth] * twiddleImaginary[twiddleThree];
            final var threeImaginary = outputReal[fourth] * twiddleImaginary[twiddleThree]
                    + outputImaginary[fourth] * twiddleReal[twiddleThree];
            final var rotatedReal = outputReal[first] - twoReal;
            final var rotatedImaginary = outputImaginary[first] - twoImaginary;
            outputReal[first] += twoReal;
            outputImaginary[first] += twoImaginary;
            final var sumReal = oneReal + threeReal;
            final var sumImaginary = oneImaginary + threeImaginary;
            final var differenceReal = oneReal - threeReal;
            final var differenceImaginary = oneImaginary - threeImaginary;
            outputReal[third] = outputReal[first] - sumReal;
            outputImaginary[third] = outputImaginary[first] - sumImaginary;
            twiddleOne += stride;
            twiddleTwo += stride * 2;
            twiddleThree += stride * 3;
            outputReal[first] += sumReal;
            outputImaginary[first] += sumImaginary;
            outputReal[second] = rotatedReal + differenceImaginary;
            outputImaginary[second] = rotatedImaginary - differenceReal;
            outputReal[fourth] = rotatedReal - differenceImaginary;
            outputImaginary[fourth] = rotatedImaginary + differenceReal;
            first++;
        }
    }

    // How many stages KissFFT splits a transform of this length into: radix 4 while it divides, then radix 2.
    private static int stageCount(final int length) {
        var stages = 0;
        var remaining = length;
        while (remaining > 1) {
            remaining /= radixOfStage(remaining);
            stages++;
        }
        return stages;
    }

    private static int radixOfStage(final int remaining) {
        if (remaining % RADIX_FOUR == 0) {
            return RADIX_FOUR;
        }
        if (remaining % RADIX_TWO == 0) {
            return RADIX_TWO;
        }
        throw new IllegalArgumentException("Frame size must be a power of two: " + 2 * remaining);
    }
}
