package dev.javax.sonaudio.format.opus;

import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.Format;
import dev.javax.sonaudio.format.Parser;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;

/**
 * Reads the metadata of an Ogg file's Opus stream (RFC 7845), and hands out an {@link OpusDecoder} of its audio. A
 * malformed file throws {@link dev.javax.sonaudio.exception.InvalidFormatException}.
 */
public final class OpusParser implements Parser {

    /**
     * Makes a parser, which keeps no state between files, so one can parse every Opus file, from any thread.
     */
    public OpusParser() {}

    @Override
    public Format format() {
        return Format.OPUS;
    }

    @Override
    public ParsedOpus parse(final SeekableByteChannel channel) throws IOException, InvalidFormatException {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
