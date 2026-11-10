/**
 * MPEG audio of Layers I, II and III, in MPEG-1, MPEG-2 and MPEG-2.5. {@link dev.javax.sonaudio.format.mp3.Mp3Parser}
 * reads a file's ID3v2 tags (versions 2.2 to 2.4), the ID3v1 tag that may end it, and its VBR header, and finds its
 * frames as FFmpeg does. {@link dev.javax.sonaudio.format.mp3.Mp3Decoder} decodes them to floating point in FFmpeg's
 * order of operations, so its samples are the ones AcoustID's {@code fpcalc} fingerprints.
 */
package dev.javax.sonaudio.format.mp3;
