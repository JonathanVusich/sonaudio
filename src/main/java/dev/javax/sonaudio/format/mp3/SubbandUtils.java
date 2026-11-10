package dev.javax.sonaudio.format.mp3;

import java.util.Arrays;
import java.util.stream.IntStream;
import lombok.experimental.UtilityClass;

/**
 * The arithmetic of Layers I and II's subband samples (ISO/IEC 11172-3 sections 2.4.3.2 and 2.4.3.3).
 */
@UtilityClass
class SubbandUtils {

    // A scale factor's index i scales by 2^(1 - i / 3).
    private static final double[] SCALES =
            IntStream.range(0, 64).mapToDouble(i -> Math.pow(2, 1 - i / 3.0)).toArray();

    /**
     * @return the end of the channels a channel's sample goes to, counting from the channel: in joint stereo, from the
     *     bound up, the left channel's goes to both
     */
    static int sharedChannels(final int subband, final int bound, final int channel, final int channelCount) {
        if (subband >= bound) {
            return channelCount;
        }
        return channel + 1;
    }

    /**
     * Joint stereo's mode extension puts the bound at subband 4, 8, 12 or 16. Other modes have none.
     *
     * @return the subband the channels share their samples from, or the limit when they share none
     */
    static int bound(final FrameHeader header, final int subbandLimit) {
        if (header.channelMode() != ChannelMode.JOINT_STEREO) {
            return subbandLimit;
        }
        return Math.min((header.modeExtension() + 1) * 4, subbandLimit);
    }

    /**
     * A sample of a class with this many steps is centred on its middle step, each step 2 / steps apart.
     *
     * @param scaleFactor the scale factor's index, from 0 to 63
     * @return the sample, scaled by the scale factor
     */
    static double requantize(final int value, final int steps, final int scaleFactor) {
        return (value - steps / 2) * 2.0 / steps * SCALES[scaleFactor];
    }

    /**
     * @return the subbands' rows of an allocation table, each picking from the same classes
     */
    static int[][] repeatedRows(final int count, final int[] classes) {
        final var rows = new int[count][];
        Arrays.fill(rows, classes);
        return rows;
    }
}
