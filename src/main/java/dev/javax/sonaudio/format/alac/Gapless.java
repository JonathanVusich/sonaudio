package dev.javax.sonaudio.format.alac;

/**
 * What iTunes' {@code iTunSMPB} tag says of the padding at the end of a track: three numbers, of which FFmpeg reads
 * these two.
 *
 * @param remainder how many samples of padding end the track
 * @param samples how many samples of audio the track has
 */
record Gapless(int remainder, int samples) {}
