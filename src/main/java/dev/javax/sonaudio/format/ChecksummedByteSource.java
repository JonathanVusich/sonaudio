package dev.javax.sonaudio.format;

import dev.javax.bitstream.ByteSource;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.Checksum;

/**
 * Feeds a bit stream the bytes of an input stream, updating checksums with each byte as it's read, as
 * {@link java.util.zip.CheckedInputStream} does. The bit stream only takes the bytes whose bits it needs, so the
 * checksums cover exactly the bytes read. It buffers the input stream itself, as the bit stream takes a byte or two at
 * a time, and a {@code BufferedInputStream}'s checks and locking on each of those reads cost more than decoding them.
 */
public final class ChecksummedByteSource implements ByteSource {

    private final InputStream inputStream;
    private final Checksum[] checksums;
    private final byte[] buffer = new byte[64 * 1024];
    private int position;
    private int limit;

    /**
     * @param checksums updated with every byte read, which the caller resets and reads as it needs
     */
    public ChecksummedByteSource(final InputStream inputStream, final Checksum... checksums) {
        this.inputStream = inputStream;
        this.checksums = checksums;
    }

    @Override
    public void read(final byte[] destination, final int numBytes) throws IOException {
        for (var i = 0; i < numBytes; i++) {
            if (!hasMoreBytes()) {
                throw new EOFException("Stream ends in the middle of the data being read!");
            }
            final var b = buffer[position++];
            destination[i] = b;
            for (final var checksum : checksums) {
                checksum.update(b);
            }
        }
    }

    /**
     * @return whether the stream has bytes left after those read, refilling the buffer once it's been read to the end
     */
    public boolean hasMoreBytes() throws IOException {
        if (position < limit) {
            return true;
        }
        final var read = inputStream.read(buffer);
        position = 0;
        limit = Math.max(read, 0);
        return read > 0;
    }

    @Override
    public void close() {
        // The caller owns the stream.
    }
}
