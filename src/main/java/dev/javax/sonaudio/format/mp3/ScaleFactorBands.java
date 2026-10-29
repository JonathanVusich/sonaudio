package dev.javax.sonaudio.format.mp3;

/**
 * How many of a granule's 576 frequency lines each scale factor band covers, at each sample rate: ISO/IEC 11172-3
 * Annex B, Table B.8 for MPEG-1, and ISO/IEC 13818-3 Table B.2 for MPEG-2. MPEG-2.5 has no spec, and its bands are the
 * ones Fraunhofer's encoder and every decoder use. The last band of each kind holds the lines above the scale factor
 * bands, which have no scale factor of their own.
 */
enum ScaleFactorBands {
    HZ_44100(
            new int[] {4, 4, 4, 4, 4, 4, 6, 6, 8, 8, 10, 12, 16, 20, 24, 28, 34, 42, 50, 54, 76, 158},
            new int[] {4, 4, 4, 4, 6, 8, 10, 12, 14, 18, 22, 30, 56}),
    HZ_48000(
            new int[] {4, 4, 4, 4, 4, 4, 6, 6, 6, 8, 10, 12, 16, 18, 22, 28, 34, 40, 46, 54, 54, 192},
            new int[] {4, 4, 4, 4, 6, 6, 10, 12, 14, 16, 20, 26, 66}),
    HZ_32000(
            new int[] {4, 4, 4, 4, 4, 4, 6, 6, 8, 10, 12, 16, 20, 24, 30, 38, 46, 56, 68, 84, 102, 26},
            new int[] {4, 4, 4, 4, 6, 8, 12, 16, 20, 26, 34, 42, 12}),
    HZ_22050(
            new int[] {6, 6, 6, 6, 6, 6, 8, 10, 12, 14, 16, 20, 24, 28, 32, 38, 46, 52, 60, 68, 58, 54},
            new int[] {4, 4, 4, 6, 6, 8, 10, 14, 18, 26, 32, 42, 18}),
    HZ_24000(
            new int[] {6, 6, 6, 6, 6, 6, 8, 10, 12, 14, 16, 18, 22, 26, 32, 38, 46, 52, 64, 70, 76, 36},
            new int[] {4, 4, 4, 6, 8, 10, 12, 14, 18, 24, 32, 44, 12}),
    HZ_16000(
            new int[] {6, 6, 6, 6, 6, 6, 8, 10, 12, 14, 16, 20, 24, 28, 32, 38, 46, 52, 60, 68, 58, 54},
            new int[] {4, 4, 4, 6, 8, 10, 12, 14, 18, 24, 30, 40, 18}),
    HZ_11025(
            new int[] {6, 6, 6, 6, 6, 6, 8, 10, 12, 14, 16, 20, 24, 28, 32, 38, 46, 52, 60, 68, 58, 54},
            new int[] {4, 4, 4, 6, 8, 10, 12, 14, 18, 24, 30, 40, 18}),
    HZ_12000(
            new int[] {6, 6, 6, 6, 6, 6, 8, 10, 12, 14, 16, 20, 24, 28, 32, 38, 46, 52, 60, 68, 58, 54},
            new int[] {4, 4, 4, 6, 8, 10, 12, 14, 18, 24, 30, 40, 18}),
    HZ_8000(
            new int[] {12, 12, 12, 12, 12, 12, 16, 20, 24, 28, 32, 40, 48, 56, 64, 76, 90, 2, 2, 2, 2, 2},
            new int[] {8, 8, 8, 12, 16, 20, 24, 28, 36, 2, 2, 2, 26});

    /**
     * How many bands a long block has, with the lines above them.
     */
    static final int LONG_BANDS = 22;

    /**
     * How many bands each window of a short block has, with the lines above them.
     */
    static final int SHORT_BANDS = 13;

    private final int[] longWidths;
    private final int[] shortWidths;

    ScaleFactorBands(final int[] longWidths, final int[] shortWidths) {
        this.longWidths = longWidths;
        this.shortWidths = shortWidths;
    }

    /**
     * @param sampleRate one of the sample rates an MPEG version has (see {@link MpegVersion#sampleRate})
     */
    static ScaleFactorBands fromSampleRate(final int sampleRate) {
        return switch (sampleRate) {
            case 44100 -> HZ_44100;
            case 48000 -> HZ_48000;
            case 32000 -> HZ_32000;
            case 22050 -> HZ_22050;
            case 24000 -> HZ_24000;
            case 16000 -> HZ_16000;
            case 11025 -> HZ_11025;
            case 12000 -> HZ_12000;
            case 8000 -> HZ_8000;
            default -> throw new IllegalArgumentException("No MPEG version has a sample rate of " + sampleRate);
        };
    }

    /**
     * @param band from 0 to {@link #LONG_BANDS} - 1
     * @return how many lines the band of a long block covers
     */
    int longBandWidth(final int band) {
        return longWidths[band];
    }

    /**
     * @param band from 0 to {@link #LONG_BANDS}, which is the end of the last band
     * @return the first line of the band in a long block
     */
    int longBandStart(final int band) {
        var start = 0;
        for (var previous = 0; previous < band; previous++) {
            start += longWidths[previous];
        }
        return start;
    }

    /**
     * @param band from 0 to {@link #SHORT_BANDS}, which is the end of the last band
     * @return the first line of the band in each of a short block's windows
     */
    int shortBandStart(final int band) {
        var start = 0;
        for (var previous = 0; previous < band; previous++) {
            start += shortWidths[previous];
        }
        return start;
    }

    /**
     * @param band from 0 to {@link #SHORT_BANDS} - 1
     * @return how many lines the band covers in each of a short block's three windows
     */
    int shortBandWidth(final int band) {
        return shortWidths[band];
    }
}
