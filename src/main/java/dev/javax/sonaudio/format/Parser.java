package dev.javax.sonaudio.format;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import dev.javax.sonaudio.exception.InvalidFormatException;

public interface Parser {

    /**
     * Reads the file's metadata, leaving the channel at its audio for the result to read from. A channel rather than a
     * stream, as some formats keep metadata after their audio (an MP3's ID3v1 tag) or spread through it. The caller
     * still owns the channel.
     */
    ParsedAudio parse(SeekableByteChannel channel) throws IOException, InvalidFormatException;

    Format format();
}
