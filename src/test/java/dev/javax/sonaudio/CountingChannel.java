package dev.javax.sonaudio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;

/**
 * A channel that counts the bytes read through it, to show how far into a file the code under test reads.
 */
final class CountingChannel implements SeekableByteChannel {

    private final SeekableByteChannel channel;
    private long bytesRead;

    CountingChannel(final SeekableByteChannel channel) {
        this.channel = channel;
    }

    long bytesRead() {
        return bytesRead;
    }

    @Override
    public int read(final ByteBuffer destination) throws IOException {
        final var read = channel.read(destination);
        if (read > 0) {
            bytesRead += read;
        }
        return read;
    }

    @Override
    public int write(final ByteBuffer source) throws IOException {
        return channel.write(source);
    }

    @Override
    public long position() throws IOException {
        return channel.position();
    }

    @Override
    public SeekableByteChannel position(final long position) throws IOException {
        channel.position(position);
        return this;
    }

    @Override
    public long size() throws IOException {
        return channel.size();
    }

    @Override
    public SeekableByteChannel truncate(final long size) throws IOException {
        channel.truncate(size);
        return this;
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
