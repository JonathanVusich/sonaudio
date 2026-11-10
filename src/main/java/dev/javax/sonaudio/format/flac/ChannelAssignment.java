package dev.javax.sonaudio.format.flac;

/**
 * How a frame codes its channels (RFC 9639 section 9.1.3): each on its own, or a stereo pair as one channel and the
 * difference of the two, or as their mean and difference.
 */
enum ChannelAssignment {
    INDEPENDENT,
    LEFT_SIDE,
    SIDE_RIGHT,
    MID_SIDE
}
