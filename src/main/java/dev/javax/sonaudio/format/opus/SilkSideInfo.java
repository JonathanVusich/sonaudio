package dev.javax.sonaudio.format.opus;

/**
 * The parameters one SILK frame codes besides its excitation: what kind of signal it is, each subframe's gain, the
 * linear prediction filter, and for a voiced frame each subframe's pitch lag and long-term filter taps.
 */
final class SilkSideInfo {

    private final SilkSignalType signalType;
    private final float[] gains;
    private final SilkLpc lpc;
    private final int[] pitchLags;
    private final float[][] ltpTaps;
    private final float ltpScale;

    /**
     * @param gains the linear gain of each subframe, which this class keeps
     * @param pitchLags the pitch lag of each subframe, which this class keeps; unused by an unvoiced frame
     * @param ltpTaps the five long-term filter taps of each subframe, which this class keeps; unused by an unvoiced
     *     frame
     * @param ltpScale how much of the long-term prediction the first subframes keep
     */
    SilkSideInfo(
            final SilkSignalType signalType,
            final float[] gains,
            final SilkLpc lpc,
            final int[] pitchLags,
            final float[][] ltpTaps,
            final float ltpScale) {
        this.signalType = signalType;
        this.gains = gains;
        this.lpc = lpc;
        this.pitchLags = pitchLags;
        this.ltpTaps = ltpTaps;
        this.ltpScale = ltpScale;
    }

    SilkSignalType signalType() {
        return signalType;
    }

    SilkLpc lpc() {
        return lpc;
    }

    float ltpScale() {
        return ltpScale;
    }

    float gain(final int subframe) {
        return gains[subframe];
    }

    int pitchLag(final int subframe) {
        return pitchLags[subframe];
    }

    float ltpTap(final int subframe, final int tap) {
        return ltpTaps[subframe][tap];
    }
}
