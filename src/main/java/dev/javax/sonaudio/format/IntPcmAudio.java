package dev.javax.sonaudio.format;

/**
 * Decoded audio whose frames are integers, as a lossless decoder such as FLAC's makes them.
 */
public non-sealed interface IntPcmAudio extends PcmAudio, Iterable<IntPcmFrame> {}
