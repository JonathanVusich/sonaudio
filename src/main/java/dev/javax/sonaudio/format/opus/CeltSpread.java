package dev.javax.sonaudio.format.opus;

/**
 * How strongly a band's pulses are spread over its coefficients by the rotation that follows their decoding (RFC 6716
 * section 4.3.4.3), which the frame's spread decision gives. Its position is the decision's symbol.
 */
enum CeltSpread {
    NONE,
    LIGHT,
    NORMAL,
    AGGRESSIVE
}
