package dev.javax.sonaudio.format;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CrcTest {

    // The check values of the CRC catalogue (reveng.sourceforge.io/crc-catalogue): the CRC of "123456789".
    private static final byte[] CHECK = "123456789".getBytes(StandardCharsets.US_ASCII);

    @Test
    void computesCrc8() {
        assertThat(crcOf(new Crc(0x07, 8))).isEqualTo(0xF4);
    }

    // CRC-16/UMTS, as FLAC, MP3 and AAC use.
    @Test
    void computesCrc16() {
        assertThat(crcOf(new Crc(0x8005, 16))).isEqualTo(0xFEE8);
    }

    // Ogg's CRC-32 is CRC-32/POSIX without its final XOR.
    @Test
    void computesCrc32() {
        assertThat(crcOf(new Crc(0x04C11DB7L, 32))).isEqualTo(0x765E7680L ^ 0xFFFFFFFFL);
    }

    @Test
    void startsOverOnReset() {
        final var crc = new Crc(0x8005, 16);
        crc.update(CHECK);
        crc.reset();

        assertThat(crcOf(crc)).isEqualTo(0xFEE8);
    }

    private static long crcOf(final Crc crc) {
        crc.update(CHECK);
        return crc.getValue();
    }
}
