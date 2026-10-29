package dev.javax.sonaudio.format.flac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.javax.bitstream.BitOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.stream.IntStream;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.exception.UncheckedInvalidFormatException;
import dev.javax.sonaudio.format.MD5Checksum;
import dev.javax.sonaudio.samples.FlacAudioSample;

class FlacDecoderTest {

    // STREAMINFO holds the MD5 of the decoded audio, so a decoder that matches it is bit-exact.
    @ParameterizedTest
    @EnumSource(FlacAudioSample.class)
    void decodesAudioMatchingItsMd5(FlacAudioSample sample)
            throws IOException, InvalidFormatException, NoSuchAlgorithmException {
        try (final var channel = sample.channel()) {
            final var decoder = new FlacParser().parse(channel).audio();
            final var streamInfo = decoder.streamInfo();

            final var decoded = decodeAll(decoder);

            assertThat(decoded.samples()).isEqualTo(streamInfo.interChannelSamples());
            assertThat(decoded.md5()).isEqualTo(streamInfo.checksum().getChecksum());
        }
    }

    // STREAMINFO may leave the length unknown (0), so the decoder reads frames until the stream ends between two.
    @ParameterizedTest
    @EnumSource(FlacAudioSample.class)
    void decodesStreamOfUnknownLength(FlacAudioSample sample)
            throws IOException, InvalidFormatException, NoSuchAlgorithmException {
        final var original = withUnknownLength(sample);
        final var decoder = new FlacParser()
                .parse(new SeekableInMemoryByteChannel(original.bytes()))
                .audio();

        final var decoded = decodeAll(decoder);

        assertThat(decoded.samples()).isEqualTo(original.streamInfo().interChannelSamples());
        assertThat(decoded.md5()).isEqualTo(original.streamInfo().checksum().getChecksum());
    }

    @Test
    void refusesStreamOfUnknownLengthCutShortInAFrame() throws IOException, InvalidFormatException {
        final var bytes = withUnknownLength(FlacAudioSample.STEREO_16_BIT_LPC).bytes();
        final var decoder = new FlacParser()
                .parse(new SeekableInMemoryByteChannel(Arrays.copyOf(bytes, bytes.length - 10)))
                .audio();

        assertThatThrownBy(() -> decodeAll(decoder))
                .isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(EOFException.class);
    }

    @Test
    void refusesStreamCutShortOfItsLength() throws IOException, InvalidFormatException {
        final byte[] bytes;
        try (final var inputStream = FlacAudioSample.STEREO_16_BIT_LPC.inputStream()) {
            bytes = inputStream.readAllBytes();
        }
        final var decoder = new FlacParser()
                .parse(new SeekableInMemoryByteChannel(Arrays.copyOf(bytes, bytes.length / 2)))
                .audio();

        assertThatThrownBy(() -> decodeAll(decoder))
                .isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(EOFException.class);
    }

    // STREAMINFO's sample rate, channel count and bit depth size what is fingerprinted, so a frame that disagrees with
    // one of them is refused rather than decoded as the wrong audio.
    @ParameterizedTest
    @CsvSource({"18, 1", "20, 16", "21, 16"})
    void refusesFramesThatDisagreeWithStreamInfo(final int byteIndex, final int flippedBits)
            throws IOException, InvalidFormatException {
        final byte[] bytes;
        try (final var inputStream = FlacAudioSample.STEREO_16_BIT_LPC.inputStream()) {
            bytes = inputStream.readAllBytes();
        }
        bytes[byteIndex] ^= (byte) flippedBits;
        final var decoder =
                new FlacParser().parse(new SeekableInMemoryByteChannel(bytes)).audio();

        assertThatThrownBy(() -> decodeAll(decoder))
                .isInstanceOf(UncheckedInvalidFormatException.class)
                .hasMessageContaining("STREAMINFO");
    }

    // Neither encoder writes escaped residual partitions, so this frame is built by hand.
    @Test
    void decodesEscapedResidualPartition() throws IOException, InvalidFormatException {
        final var header = new ByteArrayOutputStream();
        final var headerBits = BitOutputStream.wrap(header, ByteOrder.BIG_ENDIAN);
        // Sync code, then a block size stored in the next 8 bits, STREAMINFO's sample rate, mono and 8 bits a sample.
        headerBits.writeBits(0xFFF8, 16);
        headerBits.writeBits(0x60, 8);
        headerBits.writeBits(0x02, 8);
        // Frame 0, then a block of 4 samples.
        headerBits.writeBits(0, 8);
        headerBits.writeBits(3, 8);
        headerBits.flush();
        header.write(crc(header.toByteArray(), 0x07, 8));

        final var frame = new ByteArrayOutputStream();
        frame.write(header.toByteArray());
        final var frameBits = BitOutputStream.wrap(frame, ByteOrder.BIG_ENDIAN);
        // A fixed predictor of order 1, warmed up with 5.
        frameBits.writeBits(0b0_001001_0, 8);
        frameBits.writeBits(5, 8);
        // One partition of 4-bit Rice parameters, escaped to residuals of 4 bits each: 3, -2 and 7.
        frameBits.writeBits(0b00_0000_1111L, 10);
        frameBits.writeBits(4, 5);
        frameBits.writeBits(0b0011_1110_0111, 12);
        frameBits.flush();
        final var crc16 = crc(frame.toByteArray(), 0x8005, 16);
        frame.write(crc16 >> 8);
        frame.write(crc16);

        final var file = new ByteArrayOutputStream();
        final var writer = new FlacWriter();
        final var dataOutputStream = new DataOutputStream(file);
        writer.writeFlacHeader(dataOutputStream);
        writer.writeBlock(
                dataOutputStream,
                new StreamInfo(
                        new BlockHeader(BlockPosition.LAST, BlockType.STREAM_INFO, 34),
                        new MD5Checksum(new byte[16]),
                        4,
                        4,
                        0,
                        0,
                        8000,
                        1,
                        8,
                        4));
        file.write(frame.toByteArray());

        final var decoder = new FlacParser()
                .parse(new SeekableInMemoryByteChannel(file.toByteArray()))
                .audio();

        final var frames = decoder.iterator();
        final var decoded = frames.next();

        assertThat(decoded.channelCount()).isEqualTo(1);
        assertThat(IntStream.range(0, decoded.sampleCount()).map(i -> decoded.channelSample(0, i)))
                .containsExactly(5, 8, 6, 13);
        assertThat(frames.hasNext()).isFalse();
        assertThatThrownBy(frames::next).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void refusesCorruptFrame() throws IOException, InvalidFormatException {
        final byte[] bytes;
        try (final var inputStream = FlacAudioSample.STEREO_16_BIT_LPC.inputStream()) {
            bytes = inputStream.readAllBytes();
        }
        // Flip a bit in the middle of the audio, which the frame's CRC-16 catches.
        bytes[bytes.length / 2] ^= 0x10;

        final var decoder =
                new FlacParser().parse(new SeekableInMemoryByteChannel(bytes)).audio();

        assertThatThrownBy(() -> decodeAll(decoder)).isInstanceOf(UncheckedInvalidFormatException.class);
    }

    // The decoder reads its stream as it goes, so a second pass would start where the first left off.
    @Test
    void refusesSecondIteration() throws IOException, InvalidFormatException {
        try (final var channel = FlacAudioSample.STEREO_16_BIT_LPC.channel()) {
            final var decoder = new FlacParser().parse(channel).audio();
            decoder.iterator();

            assertThatThrownBy(decoder::iterator).isInstanceOf(IllegalStateException.class);
        }
    }

    private record Decoded(long samples, byte[] md5) {}

    private static Decoded decodeAll(final FlacDecoder decoder) throws NoSuchAlgorithmException {
        final var streamInfo = decoder.streamInfo();
        final var md5 = MessageDigest.getInstance("MD5");
        final var bytesPerSample = (streamInfo.bitsPerSample() + 7) / 8;
        var samples = 0L;
        for (final var frame : decoder) {
            assertThat(frame.channelCount()).isEqualTo(streamInfo.numberOfChannels());
            // The MD5 is of the samples interleaved, as little-endian signed integers in whole bytes.
            for (var i = 0; i < frame.sampleCount(); i++) {
                for (var channel = 0; channel < frame.channelCount(); channel++) {
                    final var sample = frame.channelSample(channel, i);
                    for (var b = 0; b < bytesPerSample; b++) {
                        md5.update((byte) (sample >> (8 * b)));
                    }
                }
            }
            samples += frame.sampleCount();
        }
        return new Decoded(samples, md5.digest());
    }

    private record Rewritten(byte[] bytes, StreamInfo streamInfo) {}

    /**
     * @return the sample with its STREAMINFO saying its length is unknown, and the STREAMINFO it had
     */
    private static Rewritten withUnknownLength(final FlacAudioSample sample)
            throws IOException, InvalidFormatException {
        try (final var inputStream = sample.inputStream()) {
            // Leaves the stream at the first frame.
            final var streamInfo =
                    new FlacParser().parseAllMetadata(inputStream).streamInfo().orElseThrow();
            final var output = new ByteArrayOutputStream();
            final var dataOutputStream = new DataOutputStream(output);
            final var writer = new FlacWriter();
            writer.writeFlacHeader(dataOutputStream);
            writer.writeBlock(
                    dataOutputStream,
                    new StreamInfo(
                            new BlockHeader(BlockPosition.LAST, BlockType.STREAM_INFO, 34),
                            streamInfo.checksum(),
                            streamInfo.minBlockSize(),
                            streamInfo.maxBlockSize(),
                            streamInfo.minFrameSize(),
                            streamInfo.maxFrameSize(),
                            streamInfo.sampleRate(),
                            streamInfo.numberOfChannels(),
                            streamInfo.bitsPerSample(),
                            0));
            inputStream.transferTo(output);
            return new Rewritten(output.toByteArray(), streamInfo);
        }
    }

    // A CRC with no reflection and no final XOR, as FLAC's frames use, worked out a bit at a time.
    private static int crc(final byte[] bytes, final int polynomial, final int width) {
        final var mask = (1 << width) - 1;
        var crc = 0;
        for (final var b : bytes) {
            crc ^= (b & 0xFF) << (width - 8);
            for (var bit = 0; bit < 8; bit++) {
                crc = (crc & (1 << (width - 1))) != 0 ? (crc << 1) ^ polynomial : crc << 1;
            }
        }
        return crc & mask;
    }
}
