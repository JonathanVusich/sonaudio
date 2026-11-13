package dev.javax.sonaudio.format.opus;

import java.util.Arrays;

/**
 * What one channel of the SILK decoder (the mid or the side channel) keeps from one frame to the next: the samples it
 * output and the filter's states over the last {@link #HISTORY} samples, which pitch prediction looks back into, and
 * the last frame's quantisation gain, line spectral frequencies, filter and pitch lag, which the next frame's
 * parameters are coded as differences from.
 *
 * <p>The arrays are working buffers, rewritten for every frame, and the class hands them to the decoder's stages as
 * they are, which read and write them in place.
 */
final class SilkChannelState {

    /**
     * How many samples back the decoder keeps: enough for the longest pitch lag, the long-term filter's taps and the
     * prediction filter's order.
     */
    static final int HISTORY = 322;

    private final float[] output = new float[2 * HISTORY];
    private final float[] lpcHistory = new float[2 * HISTORY];
    private final int[] nlsf = new int[SilkLpcUtils.MAX_ORDER];
    private final float[] lpc = new float[SilkLpcUtils.MAX_ORDER];

    private SilkHistory history = SilkHistory.EMPTY;
    private int logGain;
    private int primaryLag;
    private SilkSignalType previousSignalType = SilkSignalType.INACTIVE;

    SilkHistory history() {
        return history;
    }

    /**
     * @return the quantisation gain of the last subframe read, as the log gain the next is coded relative to
     */
    int logGain() {
        return logGain;
    }

    void setLogGain(final int logGain) {
        this.logGain = logGain;
    }

    /**
     * @return the last voiced frame's primary pitch lag
     */
    int primaryLag() {
        return primaryLag;
    }

    void setPrimaryLag(final int primaryLag) {
        this.primaryLag = primaryLag;
    }

    /**
     * @return the signal type of the last frame decoded to the end
     */
    SilkSignalType previousSignalType() {
        return previousSignalType;
    }

    /**
     * @return the samples after clipping, the last {@link #HISTORY} of the previous frames first and then, after
     *     {@link #HISTORY}, the frame being decoded
     */
    float[] output() {
        return output;
    }

    /**
     * @return the samples of the filter's state before clipping, laid out as {@link #output}
     */
    float[] lpcHistory() {
        return lpcHistory;
    }

    /**
     * @return the last frame's line spectral frequencies in Q15, 16 of them whatever the order
     */
    int[] nlsf() {
        return nlsf;
    }

    /**
     * @return the last frame's filter coefficients, 16 of them whatever the order
     */
    float[] lpc() {
        return lpc;
    }

    /**
     * Moves the history on past a frame of {@code frameLength} samples that has just been decoded into the arrays.
     */
    void finishFrame(final int frameLength, final SilkSignalType signalType) {
        previousSignalType = signalType;
        System.arraycopy(lpcHistory, frameLength, lpcHistory, 0, HISTORY);
        System.arraycopy(output, frameLength, output, 0, HISTORY);
        history = SilkHistory.DECODED;
    }

    /**
     * Forgets everything, as a decoder that starts again does, but only if a frame has been decoded since the last time:
     * what a frame that was only partly read (its parameters without its samples) left stays.
     */
    void reset() {
        if (history == SilkHistory.EMPTY) {
            return;
        }
        Arrays.fill(output, 0f);
        Arrays.fill(lpcHistory, 0f);
        Arrays.fill(lpc, 0f);
        Arrays.fill(nlsf, 0);
        logGain = 0;
        primaryLag = 0;
        previousSignalType = SilkSignalType.INACTIVE;
        history = SilkHistory.EMPTY;
    }
}
