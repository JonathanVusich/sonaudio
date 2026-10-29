package dev.javax.sonaudio.format;

/**
 * Decoded audio whose frames are floating-point numbers, as a decoder that works in floating point makes them.
 */
public non-sealed interface FloatPcmAudio extends PcmAudio, Iterable<FloatPcmFrame> {}
