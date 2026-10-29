package dev.javax.sonaudio.format.mp3;

import lombok.experimental.UtilityClass;

/**
 * The tables that say how many bits a granule's scale factors take, and the pre-emphasis a granule may add to them.
 */
@UtilityClass
final class ScaleFactorTables {

    /**
     * How many partitions MPEG-2's scale factors come in.
     */
    static final int LSF_PARTITIONS = 4;

    // MPEG-1's slen1 and slen2, by scalefac_compress (ISO/IEC 11172-3 section 2.4.2.7).
    private static final int[] SLEN1 = {0, 0, 0, 0, 3, 1, 1, 1, 2, 2, 2, 3, 3, 3, 4, 4};
    private static final int[] SLEN2 = {0, 1, 2, 3, 0, 1, 2, 3, 1, 2, 3, 1, 2, 3, 2, 3};

    // How many scale factors each of MPEG-2's four partitions holds (ISO/IEC 13818-3 Table 2.4.3.2), by the table
    // scalefac_compress picks, then the block layout: long, short and mixed.
    private static final int[][][] LSF_BAND_COUNTS = {
        {{6, 5, 5, 5}, {9, 9, 9, 9}, {6, 9, 9, 9}},
        {{6, 5, 7, 3}, {9, 9, 12, 6}, {6, 9, 12, 6}},
        {{11, 10, 0, 0}, {18, 18, 0, 0}, {15, 18, 0, 0}},
        {{7, 7, 7, 0}, {12, 12, 12, 0}, {6, 15, 12, 0}},
        {{6, 6, 6, 3}, {12, 9, 9, 6}, {6, 12, 9, 6}},
        {{8, 8, 5, 0}, {15, 12, 9, 0}, {6, 18, 9, 0}}
    };

    // What the preflag adds to each long band's scale factor (ISO/IEC 11172-3 Annex B, Table B.6).
    private static final int[] PRETAB = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 3, 3, 3, 2, 0};

    /**
     * @param scalefacCompress an MPEG-1 granule's scalefac_compress field
     * @return how many bits each scale factor of bands 0 to 10 takes
     */
    static int mpeg1Slen1(final int scalefacCompress) {
        return SLEN1[scalefacCompress];
    }

    /**
     * @param scalefacCompress an MPEG-1 granule's scalefac_compress field
     * @return how many bits each scale factor of bands 11 to 20 takes
     */
    static int mpeg1Slen2(final int scalefacCompress) {
        return SLEN2[scalefacCompress];
    }

    /**
     * @param table which of the six tables an MPEG-2 granule's scalefac_compress picks, from 0 to 5
     * @param partition from 0 to {@link #LSF_PARTITIONS} - 1
     * @return how many scale factors the partition holds
     */
    static int lsfBandCount(final int table, final BlockLayout layout, final int partition) {
        final var counts = LSF_BAND_COUNTS[table];
        return switch (layout) {
            case LONG -> counts[0][partition];
            case SHORT -> counts[1][partition];
            case MIXED -> counts[2][partition];
        };
    }

    /**
     * @param band a long block's scale factor band, from 0 to 21
     */
    static int pretab(final int band) {
        return PRETAB[band];
    }
}
