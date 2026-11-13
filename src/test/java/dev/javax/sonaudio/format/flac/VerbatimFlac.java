package dev.javax.sonaudio.format.flac;

import dev.javax.bitstream.BitOutputStream;
import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.internal.Crc;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

/**
 * Encodes samples a test made as a FLAC stream whose subframes are all verbatim (RFC 9639 section 9.2.4), so the test
 * can decode them with {@link FlacDecoder}, as every decoded audio is a decoder's.
 */
public final class VerbatimFlac {

    private static final int BLOCK_SIZE = 4096;
    private static final int STREAM_INFO_SIZE = 34;
    private static final int MD5_SIZE = 16;
    // Fixed block size, then the block size as 16 bits at the header's end, and the sample rate and bit depth from
    // STREAMINFO.
    private static final int FRAME_SYNC = 0b1111_1111_1111_100;
    private static final int BLOCK_SIZE_IN_16_BITS = 7;
    private static final int FROM_STREAM_INFO = 0;
    private static final int VERBATIM = 1;

    private final int sampleRate;
    private final int bitsPerSample;
    private final int[][] samples;

    /**
     * @param sampleRate samples per second, per channel
     * @param bitsPerSample how many bits each sample has
     * @param samples one array per channel, all the same length
     */
    public VerbatimFlac(final int sampleRate, final int bitsPerSample, final int[][] samples) {
        this.sampleRate = sampleRate;
        this.bitsPerSample = bitsPerSample;
        this.samples = samples;
    }

    /**
     * @return the stream's audio, parsed from it as a file's would be
     */
    public FlacDecoder audio() {
        try {
            return new FlacParser()
                    .parse(new SeekableInMemoryByteChannel(encode()))
                    .audio();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } catch (final InvalidFormatException e) {
            throw new IllegalStateException("The encoder wrote a stream the parser rejects", e);
        }
    }

    private byte[] encode() throws IOException {
        final var stream = new ByteArrayOutputStream();
        stream.write("fLaC".getBytes(StandardCharsets.US_ASCII));
        stream.write(streamInfo());
        final var sampleCount = samples[0].length;
        for (var start = 0; start < sampleCount; start += BLOCK_SIZE) {
            stream.write(frame(start / BLOCK_SIZE, start, Math.min(BLOCK_SIZE, sampleCount - start)));
        }
        return stream.toByteArray();
    }

    // The last metadata block, with no checksum of the audio.
    private byte[] streamInfo() throws IOException {
        final var block = new ByteArrayOutputStream();
        final var bits = BitOutputStream.wrap(block, ByteOrder.BIG_ENDIAN);
        bits.writeBits(1, 1);
        bits.writeBits(0, 7);
        bits.writeBits(STREAM_INFO_SIZE, 24);
        bits.writeBits(BLOCK_SIZE, 16);
        bits.writeBits(BLOCK_SIZE, 16);
        bits.writeBits(0, 24);
        bits.writeBits(0, 24);
        bits.writeBits(sampleRate, 20);
        bits.writeBits(samples.length - 1, 3);
        bits.writeBits(bitsPerSample - 1, 5);
        final long sampleCount = samples[0].length;
        bits.writeBits(sampleCount >>> 32, 4);
        bits.writeBits(sampleCount & 0xFFFF_FFFFL, 32);
        for (var i = 0; i < MD5_SIZE; i++) {
            bits.writeBits(0, 8);
        }
        bits.flush();
        return block.toByteArray();
    }

    private byte[] frame(final int number, final int start, final int blockSize) throws IOException {
        final var frame = new ByteArrayOutputStream();
        final var bits = BitOutputStream.wrap(frame, ByteOrder.BIG_ENDIAN);
        bits.writeBits(FRAME_SYNC, 15);
        bits.writeBits(0, 1);
        bits.writeBits(BLOCK_SIZE_IN_16_BITS, 4);
        bits.writeBits(FROM_STREAM_INFO, 4);
        bits.writeBits(samples.length - 1, 4);
        bits.writeBits(FROM_STREAM_INFO, 3);
        bits.writeBits(0, 1);
        writeCodedNumber(bits, number);
        bits.writeBits(blockSize - 1, 16);
        bits.flush();
        bits.writeBits(checksum(frame.toByteArray(), new Crc(0x07, 8)), 8);
        for (final var channel : samples) {
            // A zero bit, the subframe type, and no wasted bits.
            bits.writeBits(0, 1);
            bits.writeBits(VERBATIM, 6);
            bits.writeBits(0, 1);
            for (var i = start; i < start + blockSize; i++) {
                bits.writeBits(channel[i] & ((1L << bitsPerSample) - 1), bitsPerSample);
            }
        }
        bits.flush();
        bits.writeBits(checksum(frame.toByteArray(), new Crc(0x8005, 16)), 16);
        bits.flush();
        return frame.toByteArray();
    }

    // The frame number, coded as UTF-8 codes a character (RFC 9639 section 9.1.5).
    private static void writeCodedNumber(final BitOutputStream bits, final int number) throws IOException {
        if (number < 0x80) {
            bits.writeBits(number, 8);
            return;
        }
        final var continuationBytes = number < 0x800 ? 1 : number < 0x1_0000 ? 2 : 3;
        final var leadingOnes = (0xFF << (7 - continuationBytes)) & 0xFF;
        bits.writeBits(leadingOnes | (number >>> (6 * continuationBytes)), 8);
        for (var shift = 6 * (continuationBytes - 1); shift >= 0; shift -= 6) {
            bits.writeBits(0x80 | ((number >>> shift) & 0x3F), 8);
        }
    }

    private static long checksum(final byte[] bytes, final Crc crc) {
        crc.update(bytes, 0, bytes.length);
        return crc.getValue();
    }
}
