package dev.javax.sonaudio.format.opus;

/**
 * Where the audio of a redundancy frame goes: it fades in over the start of the frame it is in, when that frame follows
 * a CELT frame, or fades out over its end when a CELT frame follows.
 */
enum RedundancyPosition {
    /**
     * The redundancy replaces the start of the frame, which follows a CELT frame.
     */
    AT_START,
    /**
     * The redundancy replaces the end of the frame, which a CELT frame follows.
     */
    AT_END
}
