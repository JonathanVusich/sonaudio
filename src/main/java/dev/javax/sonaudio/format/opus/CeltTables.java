package dev.javax.sonaudio.format.opus;

import lombok.experimental.UtilityClass;

/**
 * The constant tables of CELT's frame layout and bit allocation (RFC 6716 sections 4.3 and 5.3), which FFmpeg's
 * {@code tab.c} lists and RFC 6716 gives as source code.
 */
@UtilityClass
class CeltTables {

    /**
     * How many bands CELT codes at most.
     */
    static final int BAND_COUNT = 21;

    // The first coefficient of each band at the shortest frame size (200 Hz apart), with the end of the last band as
    // well, how many coefficients each band has, and the base 2 logarithm of that in eighths of a bit.
    static final int[] BAND_START = {0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 20, 24, 28, 34, 40, 48, 60, 78, 100};
    static final int[] BAND_WIDTH = {1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 6, 6, 8, 12, 18, 22};
    static final int[] LOG_BAND_WIDTH = {0, 0, 0, 0, 0, 0, 0, 0, 8, 8, 8, 8, 16, 16, 16, 21, 21, 24, 29, 34, 36};
    // The time-frequency change a band gets by the frame's size, transient flag, select flag and the band's own flag.
    static final int[][][][] TF_SELECT = {
        {{{0, -1}, {0, -1}}, {{0, -1}, {0, -1}}},
        {{{0, -1}, {0, -2}}, {{1, 0}, {1, -1}}},
        {{{0, -2}, {0, -3}}, {{2, 0}, {1, -1}}},
        {{{0, -2}, {0, -3}}, {{3, 0}, {1, -1}}}
    };
    // The mean energy of each band that the coded energy is relative to.
    static final float[] MEAN_ENERGY = {
        6.437500f, 6.250000f, 5.750000f, 5.312500f, 5.062500f, 4.812500f, 4.500000f, 4.375000f, 4.875000f, 4.687500f,
        4.562500f, 4.437500f, 4.875000f, 4.625000f, 4.312500f, 4.500000f, 4.375000f, 4.625000f, 4.750000f, 4.437500f,
        3.750000f, 3.750000f, 3.750000f, 3.750000f, 3.750000f
    };
    // The Laplace models of the coarse energy by frame size, for inter then intra frames: the frequency of zero and the
    // decay, for each band.
    static final int[][][] COARSE_ENERGY_DIST = {
        {
            {
                72, 127, 65, 129, 66, 128, 65, 128, 64, 128, 62, 128, 64, 128, 64, 128, 92, 78, 92, 79, 92, 78, 90, 79,
                116, 41, 115, 40, 114, 40, 132, 26, 132, 26, 145, 17, 161, 12, 176, 10, 177, 11
            },
            {
                24, 179, 48, 138, 54, 135, 54, 132, 53, 134, 56, 133, 55, 132, 55, 132, 61, 114, 70, 96, 74, 88, 75, 88,
                87, 74, 89, 66, 91, 67, 100, 59, 108, 50, 120, 40, 122, 37, 97, 43, 78, 50
            }
        },
        {
            {
                83, 78, 84, 81, 88, 75, 86, 74, 87, 71, 90, 73, 93, 74, 93, 74, 109, 40, 114, 36, 117, 34, 117, 34, 143,
                17, 145, 18, 146, 19, 162, 12, 165, 10, 178, 7, 189, 6, 190, 8, 177, 9
            },
            {
                23, 178, 54, 115, 63, 102, 66, 98, 69, 99, 74, 89, 71, 91, 73, 91, 78, 89, 86, 80, 92, 66, 93, 64, 102,
                59, 103, 60, 104, 60, 117, 52, 123, 44, 138, 35, 133, 31, 97, 38, 77, 45
            }
        },
        {
            {
                61, 90, 93, 60, 105, 42, 107, 41, 110, 45, 116, 38, 113, 38, 112, 38, 124, 26, 132, 27, 136, 19, 140,
                20, 155, 14, 159, 16, 158, 18, 170, 13, 177, 10, 187, 8, 192, 6, 175, 9, 159, 10
            },
            {
                21, 178, 59, 110, 71, 86, 75, 85, 84, 83, 91, 66, 88, 73, 87, 72, 92, 75, 98, 72, 105, 58, 107, 54, 115,
                52, 114, 55, 112, 56, 129, 51, 132, 40, 150, 33, 140, 29, 98, 35, 77, 42
            }
        },
        {
            {
                42, 121, 96, 66, 108, 43, 111, 40, 117, 44, 123, 32, 120, 36, 119, 33, 127, 33, 134, 34, 139, 21, 147,
                23, 152, 20, 158, 25, 154, 26, 166, 21, 173, 16, 184, 13, 184, 10, 150, 13, 139, 15
            },
            {
                22, 178, 63, 114, 74, 82, 84, 83, 92, 82, 103, 62, 96, 72, 96, 67, 101, 73, 107, 72, 113, 55, 118, 52,
                125, 52, 118, 52, 117, 55, 135, 49, 137, 39, 157, 32, 145, 29, 97, 33, 77, 40
            }
        }
    };
    // The static allocation vectors, in 1/32 bit per sample, and the most each band may be given by frame size and
    // channel count.
    static final int[][] STATIC_ALLOC = {
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
        {90, 80, 75, 69, 63, 56, 49, 40, 34, 29, 20, 18, 10, 0, 0, 0, 0, 0, 0, 0, 0},
        {110, 100, 90, 84, 78, 71, 65, 58, 51, 45, 39, 32, 26, 20, 12, 0, 0, 0, 0, 0, 0},
        {118, 110, 103, 93, 86, 80, 75, 70, 65, 59, 53, 47, 40, 31, 23, 15, 4, 0, 0, 0, 0},
        {126, 119, 112, 104, 95, 89, 83, 78, 72, 66, 60, 54, 47, 39, 32, 25, 17, 12, 1, 0, 0},
        {134, 127, 120, 114, 103, 97, 91, 85, 78, 72, 66, 60, 54, 47, 41, 35, 29, 23, 16, 10, 1},
        {144, 137, 130, 124, 113, 107, 101, 95, 88, 82, 76, 70, 64, 57, 51, 45, 39, 33, 26, 15, 1},
        {152, 145, 138, 132, 123, 117, 111, 105, 98, 92, 86, 80, 74, 67, 61, 55, 49, 43, 36, 20, 1},
        {162, 155, 148, 142, 133, 127, 121, 115, 108, 102, 96, 90, 84, 77, 71, 65, 59, 53, 46, 30, 1},
        {172, 165, 158, 152, 143, 137, 131, 125, 118, 112, 106, 100, 94, 87, 81, 75, 69, 63, 56, 45, 20},
        {200, 200, 200, 200, 200, 200, 200, 200, 198, 193, 188, 183, 178, 173, 168, 163, 158, 153, 148, 129, 104}
    };
    static final int[][][] STATIC_CAPS = {
        {
            {224, 224, 224, 224, 224, 224, 224, 224, 160, 160, 160, 160, 185, 185, 185, 178, 178, 168, 134, 61, 37},
            {224, 224, 224, 224, 224, 224, 224, 224, 240, 240, 240, 240, 207, 207, 207, 198, 198, 183, 144, 66, 40},
        },
        {
            {160, 160, 160, 160, 160, 160, 160, 160, 185, 185, 185, 185, 193, 193, 193, 183, 183, 172, 138, 64, 38},
            {240, 240, 240, 240, 240, 240, 240, 240, 207, 207, 207, 207, 204, 204, 204, 193, 193, 180, 143, 66, 40},
        },
        {
            {185, 185, 185, 185, 185, 185, 185, 185, 193, 193, 193, 193, 193, 193, 193, 183, 183, 172, 138, 65, 39},
            {207, 207, 207, 207, 207, 207, 207, 207, 204, 204, 204, 204, 201, 201, 201, 188, 188, 176, 141, 66, 40},
        },
        {
            {193, 193, 193, 193, 193, 193, 193, 193, 193, 193, 193, 193, 194, 194, 194, 184, 184, 173, 139, 65, 39},
            {204, 204, 204, 204, 204, 204, 204, 204, 201, 201, 201, 201, 198, 198, 198, 187, 187, 175, 140, 66, 40}
        }
    };
    // The cost in eighths of a bit of the intensity band's position, by the number of bands it can be in.
    static final int[] LOG2_FRAC = {
        0, 8, 13, 16, 19, 21, 23, 24, 26, 27, 28, 29, 30, 31, 32, 32, 33, 34, 34, 35, 36, 36, 37, 37
    };
    // How the bits of a collapse mask spread out and come back together as short blocks are recombined, the order the
    // Hadamard transform puts blocks in (a run for each of 2, 4, 8 and 16 blocks, then the natural order), two to the
    // power of an eighth of an octave in Q14 (which scales the angle resolution), and the post-filter taps by tone set.
    static final int[] BIT_INTERLEAVE = {0, 1, 1, 1, 2, 3, 3, 3, 2, 3, 3, 3, 2, 3, 3, 3};
    static final int[] BIT_DEINTERLEAVE = {
        0x00, 0x03, 0x0C, 0x0F, 0x30, 0x33, 0x3C, 0x3F, 0xC0, 0xC3, 0xCC, 0xCF, 0xF0, 0xF3, 0xFC, 0xFF
    };
    static final int[] HADAMARD_ORDER = {
        1, 0, 3, 0, 2, 1, 7, 0, 4, 3, 6, 1, 5, 2, 15, 0, 8, 7, 12, 3, 11, 4, 14, 1, 9, 6, 13, 2, 10, 5, 0, 1, 2, 3, 4,
        5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
    };
    static final int[] QN_EXP2 = {16384, 17866, 19483, 21247, 23170, 25267, 27554, 30048};
    static final float[][] POSTFILTER_TAPS = {
        {0.3066406250f, 0.2170410156f, 0.1296386719f},
        {0.4638671875f, 0.2680664062f, 0.0f},
        {0.7998046875f, 0.1000976562f, 0.0f}
    };

    // The spread, allocation trim and tone set models as the inverse CDFs that {@link RangeDecoder#decodeIcdf} takes,
    // with the base 2 logarithm of each one's total. FFmpeg lists them as CDFs.
    static final int[] SPREAD_ICDF = {25, 23, 2, 0};
    static final int SPREAD_BITS = 5;
    static final int[] ALLOCATION_TRIM_ICDF = {126, 124, 119, 109, 87, 41, 19, 9, 4, 2, 0};
    static final int ALLOCATION_TRIM_BITS = 7;
    static final int[] TAPSET_ICDF = {2, 1, 0};
    static final int TAPSET_BITS = 2;
    // The model of the energy of a band that has too few bits left for a Laplace distribution: it shares the tapset's.
    static final int[] SMALL_ENERGY_ICDF = TAPSET_ICDF;
    static final int SMALL_ENERGY_BITS = TAPSET_BITS;

    // Coarse energy prediction coefficients by frame size: from the last frame (alpha), and across bands (beta).
    static final float[] ALPHA = {29440.0f / 32768.0f, 26112.0f / 32768.0f, 21248.0f / 32768.0f, 16384.0f / 32768.0f};
    static final float[] BETA = {
        1.0f - (30147.0f / 32768.0f),
        1.0f - (22282.0f / 32768.0f),
        1.0f - (12124.0f / 32768.0f),
        1.0f - (6554.0f / 32768.0f)
    };
}
