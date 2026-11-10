/**
 * FLAC, as RFC 9639 specifies it. {@link dev.javax.sonaudio.format.flac.FlacParser} reads a stream's metadata blocks,
 * mapping its Vorbis comments to {@link dev.javax.sonaudio.metadata.Tag}s, and its
 * {@link dev.javax.sonaudio.format.flac.FlacDecoder} decodes the audio frames after them to integers, checking each
 * frame's CRCs. {@link dev.javax.sonaudio.format.flac.FlacWriter} writes metadata blocks of a file's tags.
 */
package dev.javax.sonaudio.format.flac;
