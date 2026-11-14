package dev.javax.sonaudio.format.opus;

import dev.javax.sonaudio.format.ParsedAudio;
import dev.javax.sonaudio.metadata.RawMetadata;

/**
 * An Ogg file whose Opus headers have been read, left at its first audio packet.
 *
 * @param metadata the file's tags, from its {@code OpusTags} packet, and its duration
 * @param audio decodes the file's audio, from its first packet
 */
public record ParsedOpus(RawMetadata metadata, OpusDecoder audio) implements ParsedAudio {}
