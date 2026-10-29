package dev.javax.sonaudio.format.mp3;

import static org.assertj.core.api.Assertions.assertThat;

import dev.javax.bitstream.BitInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import dev.javax.sonaudio.exception.InvalidFormatException;

class HuffmanTablesTest {

    // Building each table checks that its code is complete and prefix-free.
    @ParameterizedTest
    @ValueSource(
            ints = {
                0, 1, 2, 3, 5, 6, 7, 8, 9, 10, 11, 12, 13, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
                30, 31
            })
    void definesBigValueTable(final int tableSelect) throws InvalidFormatException {
        final var table = HuffmanTables.bigValueTable(tableSelect);

        assertThat(table.size()).isPositive();
    }

    // The spec defines no tables 4 and 14, which FFmpeg reads as table 0.
    @ParameterizedTest
    @ValueSource(ints = {4, 14})
    void readsUndefinedTableAsTable0(final int tableSelect) {
        assertThat(HuffmanTables.bigValueTable(tableSelect)).isSameAs(HuffmanTables.bigValueTable(0));
    }

    // Table 1 in ISO/IEC 11172-3 Table B.7: (0, 0) is 1, (0, 1) is 001, (1, 0) is 01, and (1, 1) is 000.
    @Test
    void readsTable1AsSpecified() throws IOException, InvalidFormatException {
        final var code = HuffmanTables.bigValueTable(1).code();
        final var bits = bits("1" + "001" + "01" + "000");

        assertThat(code.readValue(bits)).isZero();
        assertThat(code.readValue(bits)).isEqualTo(1);
        assertThat(code.readValue(bits)).isEqualTo(2);
        assertThat(code.readValue(bits)).isEqualTo(3);
    }

    @Test
    void table0ReadsNoBits() throws IOException, InvalidFormatException {
        final var bits = bits("10101010");

        assertThat(HuffmanTables.bigValueTable(0).code().readValue(bits)).isZero();
        assertThat(bits.readBits(8)).isEqualTo(0b10101010);
    }

    @Test
    void sharesCodesBetweenLinbitsTables() throws InvalidFormatException {
        assertThat(HuffmanTables.bigValueTable(16).linbits()).isEqualTo(1);
        assertThat(HuffmanTables.bigValueTable(23).linbits()).isEqualTo(13);
        assertThat(HuffmanTables.bigValueTable(24).linbits()).isEqualTo(4);
        assertThat(HuffmanTables.bigValueTable(31).linbits()).isEqualTo(13);
        assertThat(HuffmanTables.bigValueTable(17).code())
                .isSameAs(HuffmanTables.bigValueTable(16).code());
    }

    // Count1 table A codes 0 as 1, and table B codes each value in four inverted bits.
    @Test
    void readsCount1Tables() throws IOException {
        assertThat(HuffmanTables.COUNT1_A.readValue(bits("1"))).isZero();
        assertThat(HuffmanTables.COUNT1_B.readValue(bits("1111"))).isZero();
        assertThat(HuffmanTables.COUNT1_B.readValue(bits("0000"))).isEqualTo(15);
    }

    // Padded with zeros, as codes look ahead past their last codeword.
    private static BitInputStream bits(final String binary) {
        final var padded = binary + "0".repeat((8 - binary.length() % 8) % 8 + 3 * Byte.SIZE);
        final var bytes = new BigInteger("1" + padded, 2).toByteArray();
        final var input = new ByteArrayInputStream(bytes, bytes.length - padded.length() / 8, padded.length() / 8);
        return BitInputStream.wrap(input, ByteOrder.BIG_ENDIAN);
    }
}
