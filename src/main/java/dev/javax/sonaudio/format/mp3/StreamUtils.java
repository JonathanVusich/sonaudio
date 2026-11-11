package dev.javax.sonaudio.format.mp3;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import lombok.experimental.UtilityClass;

@UtilityClass
final class StreamUtils {

    /**
     * Reads exactly the length into the array from the offset.
     *
     * @throws EOFException when the stream ends first
     */
    static void readFully(final InputStream input, final byte[] bytes, final int offset, final int length)
            throws IOException {
        if (input.readNBytes(bytes, offset, length) < length) {
            throw new EOFException();
        }
    }
}
