package dev.javax.sonaudio.format.opus;

/**
 * Which of the two offsets a SILK frame adds to its quantised excitation, as its frame type says (RFC 6716 section
 * 4.2.7.3).
 */
enum QuantizationOffset {
    LOW,
    HIGH
}
