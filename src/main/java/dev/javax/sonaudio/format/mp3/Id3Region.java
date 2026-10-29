package dev.javax.sonaudio.format.mp3;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * A region of an ID3v2 tag, its body or a frame, which takes the given number of the stream's bytes. An unsynchronised
 * region drops the 0 written after each 0xFF byte.
 */
final class Id3Region extends InputStream {

    private static final int END = -1;

    private final InputStream input;
    private final Synchronisation synchronisation;
    private long remaining;
    // The region's next byte, read ahead so that an inserted 0 is dropped before the region says whether it has more.
    private int next;

    Id3Region(final InputStream input, final long size, final Synchronisation synchronisation) throws IOException {
        this.input = input;
        this.synchronisation = synchronisation;
        this.remaining = size;
        this.next = readInput();
    }

    /**
     * @return the next byte, which is left to be read, when the region {@link #hasMore has more}
     */
    int peek() {
        return next;
    }

    boolean hasMore() {
        return next != END;
    }

    @Override
    public int read() throws IOException {
        final var current = next;
        next = readInput();
        if (synchronisation == Synchronisation.UNSYNCHRONISED && current == 0xFF && next == 0) {
            next = readInput();
        }
        return current;
    }

    // Skips through read, as InputStream's skip reads into a buffer, and an unsynchronised region's sizes count the
    // bytes it reads rather than the stream's.
    @Override
    public long skip(final long count) throws IOException {
        var skipped = 0L;
        while (skipped < count && hasMore()) {
            read();
            skipped++;
        }
        return skipped;
    }

    private int readInput() throws IOException {
        if (remaining == 0) {
            return END;
        }
        remaining--;
        final var nextByte = input.read();
        if (nextByte == END) {
            throw new EOFException("ID3v2 tag is cut short!");
        }
        return nextByte;
    }
}
