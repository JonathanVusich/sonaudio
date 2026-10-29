package dev.javax.sonaudio.format.mp3;

/**
 * A granule's scale factors for one channel, as {@link Mp3Decoder} reads them: one per long band, and one per short
 * band and window, laid out by band: the long bands, then each short band's three windows. The bands its layout doesn't
 * use, and those above the scale factor bands, have a scale factor of 0. In intensity stereo, the right channel's scale
 * factors are intensity positions.
 */
final class ScaleFactors {

    /**
     * How many scale factors there are: the long bands', then the short bands' by band and window.
     */
    static final int SIZE = ScaleFactorBands.LONG_BANDS + ScaleFactorBands.SHORT_BANDS * Windowing.WINDOWS;

    /**
     * The short band a mixed block's short bands start at, above its long bands.
     */
    static final int MIXED_FIRST_SHORT_BAND = 3;

    /**
     * No scale factors, all 0, which no granule before a frame's first leaves.
     */
    static final ScaleFactors NONE = new ScaleFactors(new int[SIZE], 0);

    private final int[] factors;
    private final int part2Length;

    /**
     * @param factors {@link #SIZE} scale factors, laid out by band, which the scale factors keep: the caller doesn't touch them again
     * @param part2Length how many bits the scale factors took in the main data
     */
    ScaleFactors(final int[] factors, final int part2Length) {
        this.factors = factors;
        this.part2Length = part2Length;
    }

    int longFactor(final int band) {
        return factors[band];
    }

    int shortFactor(final int band, final int window) {
        return factors[shortIndex(band, window)];
    }

    /**
     * @return how many bits the scale factors took in the main data, which start the granule's channel's part2_3_length
     */
    int part2Length() {
        return part2Length;
    }

    private static int shortIndex(final int band, final int window) {
        return ScaleFactorBands.LONG_BANDS + band * Windowing.WINDOWS + window;
    }
}
