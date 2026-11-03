package dev.javax.sonaudio.format.mp3;

import lombok.experimental.UtilityClass;

/**
 * The windows and block types of Layer III's IMDCT (ISO/IEC 11172-3 section 2.4.3.4.10).
 */
@UtilityClass
class ImdctUtils {

    // A short block's window covers 12 samples.
    private static final int SHORT_SIZE = 12;
    // A mixed block's two lowest subbands are long blocks with the normal window, whatever its block type.
    private static final int MIXED_LONG_SUBBANDS = 2;

    /**
     * @return the block type the subband is transformed with
     */
    static BlockType subbandBlockType(final int subband, final BlockType blockType, final LowSubbands lowSubbands) {
        if (lowSubbands == LowSubbands.NORMAL_WINDOW && subband < MIXED_LONG_SUBBANDS) {
            return BlockType.NORMAL;
        }
        return blockType;
    }

    /**
     * @param sine the normal block's window at the sample
     * @return the start block's window at the sample, of 36: the normal window's first half, then flat, then a short
     *     window's second half, then 0
     */
    static double startWindow(final int sample, final double sine) {
        if (sample < 18) {
            return sine;
        }
        if (sample < 24) {
            return 1;
        }
        if (sample < 30) {
            return Math.sin(Math.PI / SHORT_SIZE * (sample - 18 + 0.5));
        }
        return 0;
    }

    /**
     * @param sine the normal block's window at the sample
     * @return the stop block's window at the sample, of 36: the start block's reversed
     */
    static double stopWindow(final int sample, final double sine) {
        if (sample < 6) {
            return 0;
        }
        if (sample < 12) {
            return Math.sin(Math.PI / SHORT_SIZE * (sample - 6 + 0.5));
        }
        if (sample < 18) {
            return 1;
        }
        return sine;
    }
}
