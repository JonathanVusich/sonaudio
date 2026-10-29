package dev.javax.sonaudio.format.mp3;

import dev.javax.sonaudio.format.ParsedAudio;
import dev.javax.sonaudio.metadata.RawMetadata;

/**
 * An MP3 whose tags and stream information have been read, left at its first frame of audio.
 */
public record ParsedMp3(RawMetadata metadata, Mp3Decoder audio) implements ParsedAudio {}
