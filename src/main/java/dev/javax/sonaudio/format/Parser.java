package dev.javax.sonaudio.format;

import dev.javax.sonaudio.exception.InvalidFormatException;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;

/**
 * Reads the metadata of one format's files, and hands out a decoder for their audio. A parser keeps no state between
 * files, so one parser can parse every file of its format.
 */
public interface Parser {

    /**
     * Reads the file's metadata, leaving the channel at its audio for the result to read from. A channel rather than a
     * stream, as some formats keep metadata after their audio (an MP3's ID3v1 tag) or spread through it. The caller
     * still owns the channel.
     *
     * @param channel the file, from its start, which must stay open while its audio is decoded
     * @return the file's metadata, and a decoder of its audio from the first frame
     * @throws IOException when the channel can't be read
     * @throws InvalidFormatException when the file isn't of this format, or its metadata is malformed
     */
    ParsedAudio parse(SeekableByteChannel channel) throws IOException, InvalidFormatException;

    /**
     * @return the format this parser reads
     */
    Format format();
}
