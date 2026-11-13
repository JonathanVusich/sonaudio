package dev.javax.sonaudio.format.alac;

/**
 * The header of an MPEG-4 box.
 *
 * @param box the box's type
 * @param headerSize how many bytes the header takes: 8, or 16 when the box has a 64-bit size
 * @param payloadSize how many bytes follow the header
 */
record BoxHeader(AlacBox box, long headerSize, long payloadSize) {}
