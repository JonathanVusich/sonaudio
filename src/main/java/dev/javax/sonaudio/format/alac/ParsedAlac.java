package dev.javax.sonaudio.format.alac;

import dev.javax.sonaudio.format.ParsedAudio;
import dev.javax.sonaudio.metadata.RawMetadata;

/**
 * An MPEG-4 file whose ALAC metadata has been read, left at its audio.
 *
 * @param metadata the file's tags and duration
 * @param audio decodes the file's audio, from its first frame
 */
public record ParsedAlac(RawMetadata metadata, AlacDecoder audio) implements ParsedAudio {}
