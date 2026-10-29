package dev.javax.sonaudio.format.mp3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.javax.bitstream.BitInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class HuffmanCodeTest {

    // 0 is 0, 1 is 10, and 2 is 11.
    @Test
    void readsCodewordsOfEachLength() throws IOException {
        final var code = new HuffmanCode(new int[] {0, 1, 2}, new int[] {1, 2, 2});
        // 11, 0, 10, then padding.
        final var bits = bits((byte) 0b1101_0000);

        assertThat(code.readValue(bits)).isEqualTo(2);
        assertThat(code.readValue(bits)).isZero();
        assertThat(code.readValue(bits)).isEqualTo(1);
    }

    // Each value n below 10 is n ones then a zero, and 10 is ten ones, so 9 and 10 are too long for the root table.
    @Test
    void readsCodewordsLongerThanTheRootTable() throws IOException {
        final var code = new HuffmanCode(
                new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, new int[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 10});
        // 1111111110, 1111111111, 110, then padding.
        final var bits = bits((byte) 0b1111_1111, (byte) 0b1011_1111, (byte) 0b1111_1100, (byte) 0, (byte) 0);

        assertThat(code.readValue(bits)).isEqualTo(9);
        assertThat(code.readValue(bits)).isEqualTo(10);
        assertThat(code.readValue(bits)).isEqualTo(2);
    }

    // No codeword starts with 11.
    @Test
    void rejectsIncompleteCode() {
        assertThatThrownBy(() -> new HuffmanCode(new int[] {0, 1}, new int[] {1, 2}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 0 and 1 take every bit string, so 10 and 11 would start with 1.
    @Test
    void rejectsCodewordThatStartsAnother() {
        assertThatThrownBy(() -> new HuffmanCode(new int[] {0, 1, 2, 3}, new int[] {1, 1, 2, 2}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // After 00, the next codeword is 01, which a 1-bit codeword can't follow on from.
    @Test
    void rejectsCodewordsOutOfOrder() {
        assertThatThrownBy(() -> new HuffmanCode(new int[] {0, 1, 2}, new int[] {2, 1, 2}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static BitInputStream bits(final byte... bytes) {
        return BitInputStream.wrap(new ByteArrayInputStream(bytes), ByteOrder.BIG_ENDIAN);
    }
}
