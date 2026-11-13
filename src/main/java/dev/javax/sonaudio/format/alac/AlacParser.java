package dev.javax.sonaudio.format.alac;

import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.Format;
import dev.javax.sonaudio.format.Parser;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;

/**
 * Reads the metadata of an MPEG-4 file's ALAC audio, and hands out an {@link AlacDecoder} of its samples. A malformed
 * file throws {@link dev.javax.sonaudio.exception.InvalidFormatException}.
 */
public final class AlacParser implements Parser {

    /**
     * Makes a parser, which keeps no state between files, so one can parse every ALAC file, from any thread.
     */
    public AlacParser() {}

    @Override
    public Format format() {
        return Format.ALAC;
    }

    @Override
    public ParsedAlac parse(final SeekableByteChannel channel) throws IOException, InvalidFormatException {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
