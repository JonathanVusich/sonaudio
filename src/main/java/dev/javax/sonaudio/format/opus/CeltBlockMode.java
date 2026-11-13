package dev.javax.sonaudio.format.opus;

/**
 * Whether a CELT frame is one long block or several short ones, which its transient flag says (RFC 6716 section
 * 4.3.1): short blocks keep a sudden attack from spreading over the frame.
 */
enum CeltBlockMode {
    LONG,
    SHORT
}
