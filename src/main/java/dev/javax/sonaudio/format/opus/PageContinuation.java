package dev.javax.sonaudio.format.opus;

/**
 * Whether an Ogg page starts with the rest of a packet that began on the page before (RFC 3533 section 6, header type
 * flag 0x01).
 */
enum PageContinuation {
    FRESH,
    CONTINUED
}
