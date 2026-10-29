package dev.javax.sonaudio.format;

import static org.assertj.core.api.Assertions.assertThat;

import dev.javax.bitstream.BitInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FieldUtilsTest {

    @Test
    void readsTextUpToFirstNul() throws IOException {
        final var bits = bits("Abc\0\0\0yy");

        assertThat(FieldUtils.readNulPaddedString(bits, 6)).isEqualTo("Abc");
        // The whole field is read, padding included.
        assertThat(bits.readBits(8)).isEqualTo('y');
    }

    @Test
    void readsTextThatFillsField() throws IOException {
        assertThat(FieldUtils.readNulPaddedString(bits("Abcdefyy"), 6)).isEqualTo("Abcdef");
    }

    @Test
    void readsEmptyField() throws IOException {
        assertThat(FieldUtils.readNulPaddedString(bits("\0\0\0\0"), 4)).isEmpty();
    }

    private static BitInputStream bits(final String text) {
        final var bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        return BitInputStream.wrap(new ByteArrayInputStream(bytes), ByteOrder.BIG_ENDIAN);
    }
}
