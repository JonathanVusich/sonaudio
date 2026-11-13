package dev.javax.sonaudio.format.opus;

/**
 * CELT's random number generator (RFC 6716 section 4.3.4.4), a linear congruential generator that fills the bands
 * that have no pulses with noise and the ones that collapse. It carries on from the range decoder's state at the end
 * of the last frame.
 */
final class CeltNoise {

    private static final int MULTIPLIER = 1664525;
    private static final int INCREMENT = 1013904223;

    private int seed;

    /**
     * @return the next number: all 32 bits of the generator's state, which callers take bits of
     */
    int next() {
        seed = MULTIPLIER * seed + INCREMENT;
        return seed;
    }

    /**
     * @param newSeed the state to carry on from, which is the range decoder's range at the end of a frame
     */
    void seed(final int newSeed) {
        seed = newSeed;
    }

    /**
     * Starts from the state a decoder begins in.
     */
    void reset() {
        seed = 0;
    }
}
