package dev.javax.sonaudio.format.opus;

/**
 * Whether an Ogg page is the last of its logical stream (RFC 3533 section 6, header type flag 0x04).
 */
enum PageEnd {
    MORE,
    LAST
}
