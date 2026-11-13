package dev.javax.sonaudio.format.opus;

/**
 * Whether a channel of the SILK decoder has decoded a frame since it was last reset, so that the frame it decodes next
 * has a history to continue from.
 */
enum SilkHistory {
    EMPTY,
    DECODED
}
