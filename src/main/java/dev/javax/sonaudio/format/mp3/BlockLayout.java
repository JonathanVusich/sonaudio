package dev.javax.sonaudio.format.mp3;

/**
 * Which blocks a granule's scale factors cover: long blocks (block types 0, 1 and 3), short blocks (type 2), or short
 * blocks whose lowest bands are long (type 2 with the mixed block flag).
 */
enum BlockLayout {
    LONG,
    SHORT,
    MIXED;

    /**
     * @return how many long scale factor bands the layout has, below its short ones
     */
    int longBandCount(final MpegVersion version) {
        return switch (this) {
            case LONG -> ScaleFactorBands.LONG_BANDS;
            case SHORT -> 0;
            case MIXED -> version.mixedLongBands();
        };
    }

    /**
     * @return the short band the layout's short bands start at: a mixed block's are above its long bands
     */
    int firstShortBand() {
        return switch (this) {
            case LONG, SHORT -> 0;
            case MIXED -> ScaleFactors.MIXED_FIRST_SHORT_BAND;
        };
    }
}
