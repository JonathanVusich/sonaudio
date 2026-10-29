package dev.javax.sonaudio.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import org.junit.jupiter.api.Test;

class StreamUtilsTest {

    @Test
    void readFullyFillsTheArrayFromTheOffset() throws Exception {
        final var bytes = new byte[5];

        StreamUtils.readFully(new ByteArrayInputStream(new byte[] {1, 2, 3, 4, 5}), bytes, 2, 3);

        assertThat(bytes).containsExactly(0, 0, 1, 2, 3);
    }

    @Test
    void readFullyFailsWhenTheStreamEndsFirst() {
        final var input = new ByteArrayInputStream(new byte[] {1, 2});

        assertThatThrownBy(() -> StreamUtils.readFully(input, new byte[3], 0, 3)).isInstanceOf(EOFException.class);
    }
}
