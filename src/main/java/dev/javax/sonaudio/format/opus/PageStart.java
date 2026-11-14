package dev.javax.sonaudio.format.opus;

/**
 * Whether an Ogg page is the first of its logical stream (RFC 3533 section 6, header type flag 0x02).
 */
enum PageStart {
    FOLLOWING,
    BEGINNING
}
