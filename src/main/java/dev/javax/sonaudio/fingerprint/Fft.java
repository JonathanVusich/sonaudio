package dev.javax.sonaudio.fingerprint;

/**
 * The fast Fourier transform of real frames of one size, a power of two: an iterative radix-2 transform in doubles. It
 * reuses its working arrays from frame to frame, so one transform isn't safe to share between threads.
 */
public final class Fft {

    private final int size;
    private final int[] bitReversed;
    private final double[] cosines;
    private final double[] sines;
    private final double[] real;
    private final double[] imaginary;

    public Fft(final int size) {
        if (Integer.bitCount(size) != 1) {
            throw new IllegalArgumentException("Frame size must be a power of two: " + size);
        }
        this.size = size;
        this.bitReversed = bitReversedIndexes(size);
        this.cosines = new double[size / 2];
        this.sines = new double[size / 2];
        for (var i = 0; i < size / 2; i++) {
            final var phase = -2 * Math.PI * i / size;
            cosines[i] = Math.cos(phase);
            sines[i] = Math.sin(phase);
        }
        this.real = new double[size];
        this.imaginary = new double[size];
    }

    /**
     * @param frame {@code size} samples
     * @return the frame's power at each frequency from 0 up to half the sample rate
     */
    public PowerSpectrum powerSpectrum(final float[] frame) {
        for (var i = 0; i < size; i++) {
            real[bitReversed[i]] = frame[i];
            imaginary[i] = 0;
        }
        transform();
        final var powers = new double[size / 2 + 1];
        for (var bin = 0; bin < powers.length; bin++) {
            powers[bin] = real[bin] * real[bin] + imaginary[bin] * imaginary[bin];
        }
        return new PowerSpectrum(powers);
    }

    // Each stage combines pairs of transforms of half its length into transforms of its length.
    private void transform() {
        for (var length = 2; length <= size; length *= 2) {
            final var half = length / 2;
            final var step = size / length;
            for (var start = 0; start < size; start += length) {
                for (var offset = 0; offset < half; offset++) {
                    butterfly(start + offset, start + offset + half, offset * step);
                }
            }
        }
    }

    private void butterfly(final int low, final int high, final int twiddle) {
        final var twiddledReal = real[high] * cosines[twiddle] - imaginary[high] * sines[twiddle];
        final var twiddledImaginary = real[high] * sines[twiddle] + imaginary[high] * cosines[twiddle];
        real[high] = real[low] - twiddledReal;
        imaginary[high] = imaginary[low] - twiddledImaginary;
        real[low] += twiddledReal;
        imaginary[low] += twiddledImaginary;
    }

    private static int[] bitReversedIndexes(final int size) {
        final var bits = Integer.numberOfTrailingZeros(size);
        final var indexes = new int[size];
        for (var i = 0; i < size; i++) {
            indexes[i] = Integer.reverse(i) >>> (Integer.SIZE - bits);
        }
        return indexes;
    }
}
