package dev.javax.sonaudio.format.opus;

/**
 * Whether an Opus frame ends with a 5 ms frame of CELT redundancy, which a SILK or hybrid frame has to hide the switch
 * to a CELT frame after it, or from one before it (RFC 6716 section 4.5.1).
 */
sealed interface FrameRedundancy {

    /**
     * A frame without redundancy.
     */
    record None() implements FrameRedundancy {}

    /**
     * @param size how many bytes of the frame's end the redundancy takes
     * @param position whether it belongs before or after the frame's own samples
     */
    record Present(int size, RedundancyPosition position) implements FrameRedundancy {}
}
