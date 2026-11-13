package dev.javax.sonaudio.format.alac;

import dev.javax.sonaudio.format.IntPcmFrame;

/**
 * Whether the decoder has decoded the frame that its next call will return, which it does to find out whether there is
 * one: a packet may decode to no frame at all.
 */
sealed interface PendingFrame permits PendingFrame.Unread, PendingFrame.Ready, PendingFrame.Finished {

    /**
     * No frame has been looked for since the last was returned.
     */
    record Unread() implements PendingFrame {}

    /**
     * The next frame, which has been decoded.
     *
     * @param frame the frame
     */
    record Ready(IntPcmFrame frame) implements PendingFrame {}

    /**
     * There are no more frames.
     */
    record Finished() implements PendingFrame {}
}
