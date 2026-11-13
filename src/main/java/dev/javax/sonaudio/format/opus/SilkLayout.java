package dev.javax.sonaudio.format.opus;

/**
 * How one Opus frame's SILK layer is cut up: into 1 to 3 SILK frames of 10 or 20 ms, each of 2 or 4 subframes of 5 ms,
 * at the sample rate of the bandwidth.
 *
 * @param bandwidth narrowband, mediumband or wideband
 * @param frameCount how many SILK frames the Opus frame holds
 * @param subframes how many 5 ms subframes each SILK frame has, 2 or 4
 */
record SilkLayout(OpusBandwidth bandwidth, int frameCount, int subframes) {

    private static final int SUBFRAME_MILLIS = 5;
    private static final int MILLIS_PER_SECOND = 1000;
    private static final int WIDEBAND_ORDER = 16;
    private static final int NARROWBAND_ORDER = 10;
    private static final int LONGEST_FRAME_MILLIS = 20;

    /**
     * @param bandwidth the SILK bandwidth, narrowband to wideband
     * @param frameMillis how long the Opus frame is: 10, 20, 40 or 60
     */
    SilkLayout(final OpusBandwidth bandwidth, final int frameMillis) {
        this(
                bandwidth,
                (frameMillis + LONGEST_FRAME_MILLIS - 1) / LONGEST_FRAME_MILLIS,
                frameMillis / ((frameMillis + LONGEST_FRAME_MILLIS - 1) / LONGEST_FRAME_MILLIS) / SUBFRAME_MILLIS);
    }

    /**
     * @return how many samples a subframe has: 5 ms at 8, 12 or 16 kHz
     */
    int subframeLength() {
        return SUBFRAME_MILLIS * bandwidth.silkSampleRate() / MILLIS_PER_SECOND;
    }

    /**
     * @return how many samples a SILK frame has
     */
    int frameLength() {
        return subframes * subframeLength();
    }

    /**
     * @return how many coefficients the linear prediction filter has: 16 for wideband and 10 for the others
     */
    int order() {
        if (bandwidth == OpusBandwidth.WIDEBAND) {
            return WIDEBAND_ORDER;
        }
        return NARROWBAND_ORDER;
    }
}
