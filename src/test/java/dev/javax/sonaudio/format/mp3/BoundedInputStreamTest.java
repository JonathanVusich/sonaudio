package dev.javax.sonaudio.format.mp3;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

class BoundedInputStreamTest {

    private static BoundedInputStream bounded(final int length, final long maxCount) {
        return new BoundedInputStream(new ByteArrayInputStream(new byte[length]), maxCount);
    }

    @Test
    void endsAfterTheMaximumCount() throws Exception {
        final var stream = bounded(10, 4);

        assertThat(stream.readAllBytes()).hasSize(4);
        assertThat(stream.read()).isEqualTo(-1);
        assertThat(stream.getRemaining()).isZero();
    }

    @Test
    void countsBytesReadAndSkipped() throws Exception {
        final var stream = bounded(10, Long.MAX_VALUE);

        stream.read();
        stream.read(new byte[3], 0, 3);
        stream.skipNBytes(2);

        assertThat(stream.getCount()).isEqualTo(6);
    }

    @Test
    void doesNotSkipPastTheMaximumCount() throws Exception {
        final var stream = bounded(10, 3);

        assertThat(stream.skip(8)).isEqualTo(3);
        assertThat(stream.getCount()).isEqualTo(3);
    }

    @Test
    void skipsAsManyBytesAsTheStreamHasWhenItEndsFirst() throws Exception {
        final var stream = bounded(5, Long.MAX_VALUE);

        stream.skipAvailableBytes(8);

        assertThat(stream.getCount()).isEqualTo(5);
        assertThat(stream.read()).isEqualTo(-1);
    }

    @Test
    void doesNotCountTheEndOfTheStream() throws Exception {
        final var stream = bounded(2, 10);

        stream.readAllBytes();
        stream.read();

        assertThat(stream.getCount()).isEqualTo(2);
    }

    @Test
    void closingLeavesTheWrappedStreamOpen() throws Exception {
        final var wrapped = new ByteArrayInputStream(new byte[] {7});
        final var stream = new BoundedInputStream(wrapped, 1);

        stream.close();

        assertThat(wrapped.read()).isEqualTo(7);
    }
}
