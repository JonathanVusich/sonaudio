/**
 * What every format shares. A {@link dev.javax.sonaudio.format.Parser} reads a file's metadata through a
 * {@link java.nio.channels.SeekableByteChannel}, and its {@link dev.javax.sonaudio.format.ParsedAudio} decodes the
 * audio from the same channel a frame at a time, so nothing is decoded unless it's asked for.
 *
 * <p>Decoded audio is of one kind: {@link dev.javax.sonaudio.format.IntPcmAudio} gives the integer frames a lossless
 * format decodes to, and {@link dev.javax.sonaudio.format.FloatPcmAudio} the unrounded floating-point frames of a
 * format that decodes in floating point, such as MP3. Frames never change once they're made.
 * {@link dev.javax.sonaudio.format.AudioUtils#measureDuration} reads all of the audio to measure how long it plays.
 */
package dev.javax.sonaudio.format;
