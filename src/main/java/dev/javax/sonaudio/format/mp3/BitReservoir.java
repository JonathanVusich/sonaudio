package dev.javax.sonaudio.format.mp3;

import dev.javax.bitstream.BitInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import java.util.Arrays;
import dev.javax.sonaudio.format.StreamUtils;

/**
 * Layer III's bit reservoir (ISO/IEC 11172-3 section 2.4.3.4.1.1): a frame's main data may start in the main data of
 * the frames before it, up to 511 bytes back, so the decoder keeps the last frames' main data. This is the one place
 * the decoder reads bytes into an array: they're read again when a later frame reaches back into them.
 */
final class BitReservoir {

    // The most of a frame's main data FFmpeg keeps for the next frame, and of what it kept before that.
    private static final int BACKSTEP = 512;
    // The most main data a frame can hold: a free-format MPEG-2.5 frame at 640 kbit/s and 8 kHz.
    private static final int MAX_MAIN_DATA_SIZE = 72 * 640_000 / 8000 + 1;
    // Zeros after the main data, as FFmpeg's buffers have, which a granule's channel reads when its part2_3_length runs
    // past the main data: up to its 4095 bits, and the 19 a Huffman code is looked up by peeking.
    private static final int PADDING = (4095 + 19) / Byte.SIZE + 1;

    private final byte[] bytes = new byte[2 * BACKSTEP + MAX_MAIN_DATA_SIZE + PADDING];
    private int size;
    // How many bytes at the end of the last frame's main data the file didn't have, which are zeros.
    private int missingBytes;

    /**
     * Reads a frame's main data from the stream into the reservoir. The frame's granules start the side information's
     * main_data_begin bytes before the frame's own main data, which must be read before the next frame's main data is.
     *
     * @param input the frame's stream, at its main data
     * @param presentBytes how many bytes of the frame the file has, fewer than its length when it cuts the frame short
     * @return the frame, whose granules may start before the reservoir's first byte, when the stream was cut from a
     *     longer one
     */
    Mp3Frame readMainData(
            final InputStream input, final FrameHeader header, final SideInfo sideInfo, final int presentBytes)
            throws IOException {
        final var granulesStart = size - sideInfo.mainDataBegin();
        StreamUtils.readFully(input, bytes, size, header.mainDataSize());
        size += header.mainDataSize();
        missingBytes = Math.min(header.frameLength() - presentBytes, header.mainDataSize());
        Arrays.fill(bytes, size, size + PADDING, (byte) 0);
        return new Mp3Frame(header, sideInfo, granulesStart);
    }

    /**
     * Starts the reservoir again with the end of a frame's main data alone, for a frame FFmpeg drops, which keeps only
     * the last 512 bytes of that frame for the frames after it.
     *
     * @param input the frame's stream, at its main data
     */
    void restart(final InputStream input, final FrameHeader header) throws IOException {
        StreamUtils.readFully(input, bytes, 0, header.mainDataSize());
        size = header.mainDataSize();
        keepLast(Math.min(BACKSTEP, size));
    }

    /**
     * Keeps only the bytes after where the frame's granules end, as FFmpeg does, for the next frame to reach back into:
     * the rest of the frames' main data before this frame's, up to 512 bytes, and the rest of this frame's, of which it
     * keeps only the last 512 bytes when there are more, or when the granules run past it.
     *
     * @param granuleBits how many bits the frame's granules take, from where they start
     */
    void keepAfterGranules(final Mp3Frame frame, final int granuleBits) {
        final var frameStart = size - frame.header().mainDataSize();
        final var end = Math.max(frame.granulesStart() + Math.ceilDiv(granuleBits, Byte.SIZE), 0);
        if (end > size || size - Math.max(end, frameStart) > BACKSTEP) {
            keepLast(Math.min(BACKSTEP, frame.header().mainDataSize()));
            return;
        }
        if (end >= frameStart) {
            keepLast(size - end);
            return;
        }
        // The old bytes FFmpeg keeps only when there are no more than 512 of them.
        final var oldBytes = frameStart - end;
        final var start = oldBytes <= BACKSTEP ? end : frameStart;
        keepLast(size - start);
    }

    private void keepLast(final int count) {
        System.arraycopy(bytes, size - count, bytes, 0, count);
        size = count;
    }

    /**
     * @param bitOffset how many bits after the frame's granules start to read from
     * @return how many bits the file has from there to the end of the frame's main data
     */
    int presentBitsAt(final Mp3Frame frame, final int bitOffset) {
        final var position = frame.granulesStart() * Byte.SIZE + bitOffset;
        if (position < (size - frame.header().mainDataSize()) * Byte.SIZE) {
            return Integer.MAX_VALUE;
        }
        return (size - missingBytes) * Byte.SIZE - position;
    }

    /**
     * @param bitOffset how many bits after the frame's granules start to read from, which must be in the reservoir
     * @return the reservoir's bits from there, then zeros
     */
    BitInputStream bitsAt(final Mp3Frame frame, final int bitOffset) throws IOException {
        final var position = frame.granulesStart() * Byte.SIZE + bitOffset;
        final var start = position / Byte.SIZE;
        final var bits = BitInputStream.wrap(
                new ByteArrayInputStream(bytes, start, size + PADDING - start), ByteOrder.BIG_ENDIAN);
        final var skipped = position % Byte.SIZE;
        if (skipped > 0) {
            bits.readBits(skipped);
        }
        return bits;
    }
}
