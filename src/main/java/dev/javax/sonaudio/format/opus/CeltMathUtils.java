package dev.javax.sonaudio.format.opus;

import lombok.experimental.UtilityClass;

/**
 * Integer arithmetic that CELT's decoding shares.
 */
@UtilityClass
class CeltMathUtils {

    /**
     * Limits a value to a range the way FFmpeg's {@code av_clip} does, which unlike {@link Math#clamp} accepts a range
     * whose end is below its start, in which case a value above the end gives the end.
     *
     * @param value the value
     * @param low the least it may be
     * @param high the most it may be, which is also what a value above both ends gives
     * @return the value, or the end of the range it is past
     */
    static int clip(final int value, final int low, final int high) {
        if (value < low) {
            return low;
        }
        if (value > high) {
            return high;
        }
        return value;
    }
}
