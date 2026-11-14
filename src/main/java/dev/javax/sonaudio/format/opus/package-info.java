/**
 * Opus, as RFC 6716 specifies it, in an Ogg file, as RFC 7845 specifies. {@link dev.javax.sonaudio.format.opus.OpusParser}
 * reads a file's headers and its Vorbis comments, and its {@link dev.javax.sonaudio.format.opus.OpusDecoder} decodes
 * the audio to floating point at 48000 Hz: SILK, CELT and hybrid frames, with the 5 ms redundancy frames and mode
 * switches, in mono, stereo and multistream layouts.
 *
 * <p>The decoder follows FFmpeg's native Opus decoder, not libopus, quirk for quirk, and its samples are within 1 of
 * FFmpeg's at 16 bits for every fixture and every vector of FFmpeg's FATE suite, with the same number of them. They are
 * not bit for bit, as FFmpeg's own output differs by 1 between its C and SIMD code.
 */
package dev.javax.sonaudio.format.opus;
