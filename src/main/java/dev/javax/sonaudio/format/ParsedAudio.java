package dev.javax.sonaudio.format;

import dev.javax.sonaudio.metadata.RawMetadata;

/**
 * An audio file whose metadata a {@link Parser} has read.
 */
public interface ParsedAudio {

    RawMetadata metadata();

    /**
     * @return decodes the file's audio from the stream it was parsed from, so only while that stream is still open
     */
    PcmAudio audio();
}
