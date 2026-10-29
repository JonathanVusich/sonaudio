package dev.javax.sonaudio.format.mp3;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A stream that ends after a number of bytes, and counts those read or skipped, which the decoder takes for its position
 * in the audio. Closing it leaves the stream it wraps open.
 */
final class BoundedInputStream extends FilterInputStream {

    private final long maxCount;
    private long count;

    BoundedInputStream(final InputStream input, final long maxCount) {
        super(input);
        this.maxCount = maxCount;
    }

    /**
     * @return how many bytes have been read or skipped
     */
    long getCount() {
        return count;
    }

    /**
     * @return how many more bytes the stream gives before it ends
     */
    long getRemaining() {
        return maxCount - count;
    }

    /**
     * Skips the bytes, or as many as the stream has left when it ends first, which a frame the file cuts short does.
     */
    void skipAvailableBytes(final long length) throws IOException {
        var remaining = length;
        while (remaining > 0) {
            final var skipped = skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
            } else if (read() >= 0) {
                remaining--;
            } else {
                return;
            }
        }
    }

    @Override
    public int read() throws IOException {
        if (getRemaining() <= 0) {
            return -1;
        }
        final var read = super.read();
        if (read >= 0) {
            count++;
        }
        return read;
    }

    @Override
    public int read(final byte[] bytes, final int offset, final int length) throws IOException {
        if (getRemaining() <= 0) {
            return -1;
        }
        final var read = super.read(bytes, offset, (int) Math.min(length, getRemaining()));
        if (read > 0) {
            count += read;
        }
        return read;
    }

    @Override
    public long skip(final long length) throws IOException {
        final var skipped = super.skip(Math.min(length, getRemaining()));
        count += skipped;
        return skipped;
    }

    @Override
    public int available() throws IOException {
        return (int) Math.min(super.available(), getRemaining());
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public void mark(final int readLimit) {}

    @Override
    public void reset() throws IOException {
        throw new IOException("mark/reset not supported");
    }

    @Override
    public void close() {}
}
