package dev.javax.sonaudio.format.opus;

/**
 * The linear prediction filters of one SILK frame: the filter of the frame's own line spectral frequencies, and, when
 * the frame interpolates between them and the last frame's, a second filter for its first two subframes.
 */
final class SilkLpc {

    /**
     * Whether the first two subframes of a frame are filtered by an interpolation of the last frame's filter and this
     * one's.
     */
    enum LeadIn {
        ABSENT,
        PRESENT
    }

    private final float[] body;
    private final float[] leadIn;
    private final LeadIn leadInUse;
    private final int interpolationFactor;

    /**
     * @param body the coefficients of the frame's filter, 16 entries; this class keeps them, and nothing else changes
     *     them
     * @param leadIn the coefficients of the first two subframes' filter, 16 entries, which {@code leadInUse} says are
     *     used or not
     * @param interpolationFactor 0 to 4, how far the lead-in filter is from the last frame's towards this frame's,
     *     in quarters: 4 means it is not interpolated
     */
    SilkLpc(final float[] body, final float[] leadIn, final LeadIn leadInUse, final int interpolationFactor) {
        this.body = body;
        this.leadIn = leadIn;
        this.leadInUse = leadInUse;
        this.interpolationFactor = interpolationFactor;
    }

    int interpolationFactor() {
        return interpolationFactor;
    }

    /**
     * @param subframe the subframe's index in its frame
     * @param tap the coefficient's index, from 0
     * @return the filter coefficient that the subframe is filtered by
     */
    float coefficient(final int subframe, final int tap) {
        if (subframe < 2 && leadInUse == LeadIn.PRESENT) {
            return leadIn[tap];
        }
        return body[tap];
    }
}
