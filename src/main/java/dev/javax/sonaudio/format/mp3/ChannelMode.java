package dev.javax.sonaudio.format.mp3;

/**
 * How a frame codes its channels, in the order of the header's two mode bits.
 */
public enum ChannelMode {
    STEREO,
    JOINT_STEREO,
    DUAL_CHANNEL,
    MONO
}
