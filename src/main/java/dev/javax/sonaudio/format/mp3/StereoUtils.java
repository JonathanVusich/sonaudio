package dev.javax.sonaudio.format.mp3;

import lombok.experimental.UtilityClass;

/**
 * Finds where intensity stereo starts in a granule's right channel (ISO/IEC 11172-3 section 2.4.3.4.9.3, ISO/IEC
 * 13818-3 section 2.4.3.2): above the last band with a value that isn't 0, on the lines in the order they're coded,
 * before short bands are reordered.
 */
@UtilityClass
class StereoUtils {

    /**
     * @return the band above the last long one with a value, which is where intensity coding starts, or 0 when none
     *     has
     */
    static int firstIntensityLongBand(final ScaleFactorBands bands, final Spectrum right, final int longBands) {
        for (var band = longBands - 1; band >= 0; band--) {
            if (hasValue(right, bands.longBandStart(band), bands.longBandWidth(band))) {
                return band + 1;
            }
        }
        return 0;
    }

    /**
     * @return the band above the window's last short one with a value, or the first short band when none has
     */
    static int firstIntensityShortBand(
            final ScaleFactorBands bands, final Spectrum right, final int firstBand, final int window) {
        for (var band = ScaleFactorBands.SHORT_BANDS - 1; band >= firstBand; band--) {
            if (hasValue(right, shortLineStart(bands, band, window), bands.shortBandWidth(band))) {
                return band + 1;
            }
        }
        return firstBand;
    }

    /**
     * Before reordering, each short band holds its windows one after another.
     *
     * @return the line the window's part of the short band starts at
     */
    static int shortLineStart(final ScaleFactorBands bands, final int band, final int window) {
        return Windowing.WINDOWS * bands.shortBandStart(band) + window * bands.shortBandWidth(band);
    }

    private static boolean hasValue(final Spectrum spectrum, final int start, final int width) {
        for (var line = start; line < start + width; line++) {
            if (spectrum.line(line) != 0) {
                return true;
            }
        }
        return false;
    }
}
