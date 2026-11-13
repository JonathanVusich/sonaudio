package dev.javax.sonaudio.format.opus;

/**
 * The pitch post-filter a CELT frame asks for (RFC 6716 section 4.3.7.1): a comb filter with three taps around a
 * pitch period. A frame with none has gains of 0.
 *
 * @param period the pitch period in samples, from 15 to 1022
 * @param gains the gains of the filter's three taps: the centre one and the two pairs either side of it
 */
record CeltPostfilterParameters(int period, float[] gains) {

    /**
     * What a frame that has no post-filter has: no gain.
     */
    static CeltPostfilterParameters none() {
        return new CeltPostfilterParameters(0, new float[3]);
    }
}
