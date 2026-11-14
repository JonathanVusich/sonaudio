/**
 * ALAC, Apple Lossless, in an MPEG-4 file. {@link dev.javax.sonaudio.format.alac.AlacParser} reads a file's metadata,
 * and its {@link dev.javax.sonaudio.format.alac.AlacDecoder} decodes the audio to integers: bit for bit what FFmpeg's
 * decoder gives, for 16 and 24 bits at every compression level that FFmpeg's encoder writes, and trimmed by the file's
 * edit list and iTunes' gapless tag as FFmpeg's demuxer trims it. Nothing available writes 20-bit or 32-bit ALAC, so
 * those depths are decoded as the format says and are not checked against FFmpeg.
 */
package dev.javax.sonaudio.format.alac;
