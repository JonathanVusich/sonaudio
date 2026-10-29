package dev.javax.sonaudio.fingerprint;

/**
 * A frame's power at each frequency an {@link Fft} resolves, from 0 up to half the sample rate.
 */
public final class PowerSpectrum {

    private final double[] powers;

    /**
     * @param powers which the spectrum takes ownership of
     */
    public PowerSpectrum(final double[] powers) {
        this.powers = powers;
    }

    /**
     * @return the power of the frequency {@code bin} times the sample rate over the frame size
     */
    public double power(final int bin) {
        return powers[bin];
    }
}
