package dev.javax.sonaudio.format.opus;

/**
 * Whether a channel of a CELT frame is the average of the two it codes, which a stereo frame is output as in a mono
 * stream, or is output as it is coded.
 */
enum DownmixMode {
    NONE,
    AVERAGE
}
