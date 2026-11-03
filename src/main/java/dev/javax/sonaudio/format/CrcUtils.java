package dev.javax.sonaudio.format;

import lombok.experimental.UtilityClass;

/**
 * Builds the lookup tables of {@link Crc}.
 */
@UtilityClass
class CrcUtils {

    /**
     * @param polynomial without its top bit
     * @param width from 8 to 32 bits
     * @return the CRC of each byte on its own, by byte
     */
    static long[] crcTable(final long polynomial, final int width) {
        final var mask = (1L << width) - 1;
        final var topBit = 1L << (width - 1);
        final var table = new long[256];
        for (var value = 0; value < 256; value++) {
            var crc = (long) value << (width - 8);
            for (var bit = 0; bit < 8; bit++) {
                if ((crc & topBit) != 0) {
                    crc = (crc << 1) ^ polynomial;
                } else {
                    crc = crc << 1;
                }
            }
            table[value] = crc & mask;
        }
        return table;
    }
}
