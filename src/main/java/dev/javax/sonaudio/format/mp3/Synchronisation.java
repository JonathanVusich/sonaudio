package dev.javax.sonaudio.format.mp3;

/**
 * Whether a region of an ID3v2 tag is unsynchronised: written with a 0 after each 0xFF byte, so no tag data looks like
 * a frame sync (ID3v2.4.0 structure section 6.1).
 */
enum Synchronisation {
    SYNCHRONISED,
    UNSYNCHRONISED
}
