package dev.javax.sonaudio.format.flac;

import dev.javax.bitstream.BitInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import dev.javax.sonaudio.format.ChecksummedByteSource;
import dev.javax.sonaudio.format.Crc;

/**
 * Reads the bits of FLAC frames, most significant first, and keeps the CRC-8 of the frame header and the CRC-16 of
 * the whole frame (RFC 9639 sections 9.1.8 and 9.3) over the bytes read since the frame started.
 */
final class FrameReader {

    private final Crc crc8 = new Crc(0x07, 8);
    private final Crc crc16 = new Crc(0x8005, 16);
    private final ChecksummedByteSource bytes;
    private final BitInputStream bits;

    FrameReader(final InputStream inputStream) {
        this.bytes = new ChecksummedByteSource(inputStream, crc8, crc16);
        this.bits = BitInputStream.wrap(bytes, ByteOrder.BIG_ENDIAN);
    }

    /**
     * Starts the CRCs over for a new frame.
     */
    void startFrame() {
        crc8.reset();
        crc16.reset();
    }

    /**
     * @param count from 1 to 63
     */
    long readBits(final int count) throws IOException {
        return bits.readBits(count);
    }

    /**
     * @param count from 1 to 63
     */
    long readSigned(final int count) throws IOException {
        final var shift = 64 - count;
        return (bits.readBits(count) << shift) >> shift;
    }

    /**
     * @return the number of 0 bits before the next 1 bit, which is read too
     */
    int readUnary() throws IOException {
        final var zeros = bits.countZeros();
        // The count stops at the end of the input too, where reading the 1 bit throws.
        bits.readBits(1);
        return zeros;
    }

    /**
     * Skips to the start of the next byte, as each frame's subframes are padded to whole bytes.
     */
    void alignToByte() {
        bits.alignToByte();
    }

    /**
     * @return the CRC-8 of the frame so far: 0 once a frame header has been read along with its CRC
     */
    long crc8() {
        return crc8.getValue();
    }

    /**
     * @return the CRC-16 of the frame so far: 0 once a whole frame has been read along with its CRC
     */
    long crc16() {
        return crc16.getValue();
    }

    /**
     * @return whether the stream has bytes left after those read, which between frames means another frame
     */
    boolean hasMoreBytes() throws IOException {
        return bytes.hasMoreBytes();
    }
}
