package dev.javax.sonaudio.internal;

/**
 * Where the next output sample of a {@link ResamplerFilter} falls in its input: the position of its first input sample,
 * the phase of the filter, and how far it is on towards the next phase. It steps from one output sample to the next as
 * libswresample does.
 */
public final class ResamplerCursor {

    private int position;
    private int phase;
    private int fraction;

    /**
     * @param position the index of the first input sample that the first output sample's filter covers, which is
     *     negative when the filter reaches before the audio
     */
    public ResamplerCursor(final int position) {
        this.position = position;
    }

    /**
     * @return the index of the first input sample that the next output sample's filter covers
     */
    public int position() {
        return position;
    }

    /**
     * @return the phase of the filter that makes the next output sample
     */
    public int phase() {
        return phase;
    }

    /**
     * @return how far the next output sample is from its phase to the next, out of {@link
     *     ResamplerFilter#sourceIncrement}
     */
    public int fraction() {
        return fraction;
    }

    /**
     * Moves on to the next output sample.
     *
     * @param filter the filter that makes the output
     */
    public void advance(final ResamplerFilter filter) {
        fraction += filter.incrementRemainder();
        phase += filter.incrementQuotient();
        if (fraction >= filter.sourceIncrement()) {
            fraction -= filter.sourceIncrement();
            phase++;
        }
        position += phase / filter.phaseCount();
        phase %= filter.phaseCount();
    }

    /**
     * Moves the position back, when the input before it is dropped.
     *
     * @param samples how many samples were dropped from the start of the input
     */
    public void dropInput(final int samples) {
        position -= samples;
    }
}
