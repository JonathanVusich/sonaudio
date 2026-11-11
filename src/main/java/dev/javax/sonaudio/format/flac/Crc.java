package dev.javax.sonaudio.format.flac;

import java.util.zip.Checksum;

/**
 * A table-driven CRC of up to 32 bits with no reflection, a zero initial value and no final XOR, as audio formats
 * use: CRC-8 (0x07) and CRC-16 (0x8005) in FLAC frames (RFC 9639 sections 9.1.8 and 9.3), CRC-16 (0x8005) in MP3
 * and AAC ADTS frames, and CRC-32 (0x04C11DB7) in Ogg pages. The JDK's {@code CRC32} is reflected, so it can't serve.
 */
final class Crc implements Checksum {

    private final int width;
    private final long mask;
    private final long[] table;
    private long value;

    /**
     * @param polynomial without its top bit
     * @param width from 8 to 32 bits
     */
    Crc(final long polynomial, final int width) {
        this.width = width;
        this.mask = (1L << width) - 1;
        this.table = CrcUtils.crcTable(polynomial, width);
    }

    @Override
    public void update(final int nextByte) {
        value = ((value << 8) ^ table[(int) ((value >>> (width - 8)) ^ (nextByte & 0xFF))]) & mask;
    }

    @Override
    public void update(final byte[] bytes, final int offset, final int length) {
        for (var i = offset; i < offset + length; i++) {
            update(bytes[i]);
        }
    }

    @Override
    public long getValue() {
        return value;
    }

    @Override
    public void reset() {
        value = 0;
    }
}
