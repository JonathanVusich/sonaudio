package dev.javax.sonaudio.format.opus;

/**
 * The pitch post-filter a CELT frame asks for (RFC 6716 section 4.3.7.1): a comb filter with three taps around a
 * pitch period. A frame with none has gains of 0.
 *
 * @param period the pitch period in samples, from 15 to 1022
 * @param centreGain the gain of the tap at the period
 * @param nearGain the gain of the two taps a sample either side of it
 * @param farGain the gain of the two taps two samples either side of it
 */
record CeltPostfilterParameters(int period, float centreGain, float nearGain, float farGain) {

    /**
     * What a frame that has no post-filter has: no gain.
     */
    static final CeltPostfilterParameters NONE = new CeltPostfilterParameters(0, 0f, 0f, 0f);
}
