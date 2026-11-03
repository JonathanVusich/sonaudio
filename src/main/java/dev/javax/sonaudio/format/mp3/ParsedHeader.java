package dev.javax.sonaudio.format.mp3;

/**
 * Whether four bytes make a frame header.
 */
sealed interface ParsedHeader permits ParsedHeader.HeaderFound, ParsedHeader.NoHeader {

    record HeaderFound(FrameHeader header) implements ParsedHeader {}

    /**
     * The bytes don't start with a frame sync, or have a field the spec reserves.
     */
    record NoHeader() implements ParsedHeader {}
}
