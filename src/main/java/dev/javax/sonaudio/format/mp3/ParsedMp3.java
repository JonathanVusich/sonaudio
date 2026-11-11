package dev.javax.sonaudio.format.mp3;

import dev.javax.sonaudio.format.ParsedAudio;
import dev.javax.sonaudio.metadata.RawMetadata;

/**
 * An MP3 whose tags and stream information have been read, left at its first frame of audio.
 *
 * @param metadata the file's tags, from its ID3v2 and ID3v1 tags, and its duration
 * @param audio decodes the file's audio, from its first frame
 */
public record ParsedMp3(RawMetadata metadata, Mp3Decoder audio) implements ParsedAudio {}
