package dev.javax.sonaudio.format.opus;

/**
 * How the bands of a stereo CELT frame below its intensity band are coded (RFC 6716 section 4.3.4.5): joint, as mid
 * and side with an angle between them, or dual, as two independent channels.
 */
enum CeltStereoMode {
    JOINT,
    DUAL
}
