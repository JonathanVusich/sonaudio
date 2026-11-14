package dev.javax.sonaudio.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CrcUtilsTest {

    // A byte's CRC is the polynomial times the byte, modulo the polynomial with its top bit, so 1 gives the polynomial
    // and 2 gives it shifted while it fits.
    @Test
    void crcTableMultipliesEachByteByThePolynomial() {
        final var table = CrcUtils.crcTable(0x07, 8);

        assertThat(table).hasSize(256);
        assertThat(table[0]).isZero();
        assertThat(table[1]).isEqualTo(0x07);
        assertThat(table[2]).isEqualTo(0x0E);
    }

    @Test
    void crcTableKeepsTheWidth() {
        assertThat(CrcUtils.crcTable(0x8005, 16)[1]).isEqualTo(0x8005);
        assertThat(CrcUtils.crcTable(0x04C11DB7, 32)[1]).isEqualTo(0x04C11DB7);
        for (final var value : CrcUtils.crcTable(0x8005, 16)) {
            assertThat(value).isBetween(0L, 0xFFFFL);
        }
    }
}
