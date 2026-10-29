package dev.javax.sonaudio.format.flac;

import dev.javax.sonaudio.format.ParsedAudio;
import dev.javax.sonaudio.metadata.RawMetadata;

/**
 * A FLAC stream whose metadata blocks have been read. Its audio is only read as {@code audio} is iterated, a frame at
 * a time, from the stream it was parsed from, so it can only be iterated while that stream is still open.
 *
 * @param audio decodes the stream's audio frames, starting at the first
 */
public record ParsedFlac(RawMetadata metadata, FlacDecoder audio) implements ParsedAudio {}
