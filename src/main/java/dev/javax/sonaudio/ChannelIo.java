package dev.javax.sonaudio;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.channels.SeekableByteChannel;

/**
 * The read and seek callbacks of an FFmpeg {@code AVIOContext}, over a channel. An exception thrown through a native
 * frame would kill the JVM, so every failure is returned as an FFmpeg error code.
 */
final class ChannelIo {

    private static final int SEEK_SET = 0;
    private static final int SEEK_CUR = 1;
    private static final int SEEK_END = 2;

    private final SeekableByteChannel channel;

    ChannelIo(final SeekableByteChannel channel) {
        this.channel = channel;
    }

    MemorySegment readCallback(final Arena arena) {
        final var descriptor = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT);
        return upcall(
                arena,
                "read",
                MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, int.class),
                descriptor);
    }

    MemorySegment seekCallback(final Arena arena) {
        final var descriptor = FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT);
        return upcall(
                arena,
                "seek",
                MethodType.methodType(long.class, MemorySegment.class, long.class, int.class),
                descriptor);
    }

    private MemorySegment upcall(
            final Arena arena, final String method, final MethodType type, final FunctionDescriptor descriptor) {
        try {
            final var handle = MethodHandles.lookup()
                    .findVirtual(ChannelIo.class, method, type)
                    .bindTo(this);
            return Linker.nativeLinker().upcallStub(handle, descriptor, arena);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException("ChannelIo has no method %s".formatted(method), e);
        }
    }

    int read(final MemorySegment opaque, final MemorySegment buffer, final int size) {
        try {
            final var read = channel.read(buffer.reinterpret(size).asByteBuffer());
            if (read < 0) {
                return FfmpegLibrary.AVERROR_EOF;
            }
            return read;
        } catch (IOException | RuntimeException _) {
            return FfmpegLibrary.AVERROR_EIO;
        }
    }

    long seek(final MemorySegment opaque, final long offset, final int whence) {
        try {
            if ((whence & FfmpegLibrary.AVSEEK_SIZE) != 0) {
                return channel.size();
            }
            return seekTo(offset, whence & ~FfmpegLibrary.AVSEEK_FORCE);
        } catch (IOException | RuntimeException _) {
            return FfmpegLibrary.AVERROR_EIO;
        }
    }

    private long seekTo(final long offset, final int whence) throws IOException {
        return switch (whence) {
            case SEEK_SET -> channel.position(offset).position();
            case SEEK_CUR -> channel.position(channel.position() + offset).position();
            case SEEK_END -> channel.position(channel.size() + offset).position();
            default -> FfmpegLibrary.AVERROR_EINVAL;
        };
    }
}
