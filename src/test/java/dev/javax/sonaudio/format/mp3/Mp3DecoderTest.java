package dev.javax.sonaudio.format.mp3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.javax.bitstream.BitInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.TreeSet;
import java.util.stream.IntStream;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.PcmFrame;
import dev.javax.sonaudio.samples.Mp3Sample;

class Mp3DecoderTest {

    // ISO/IEC 11172-4 counts a decoder compliant when its 16-bit samples are within 1 of the reference decoder's.
    private static final int TOLERANCE = 1;
    // An MPEG-1 frame's samples, in stereo.
    private static final int FRAME_SAMPLES = 2 * 2 * FrameHeader.GRANULE_SIZE;

    // Each frame's header gives how long it is, so every frame is only decoded when each is read right, and the samples
    // add up to what the stream's VBR header or frames say. The file cut short of its Info header has fewer frames than
    // the header counts.
    @ParameterizedTest
    @EnumSource(
            value = Mp3Sample.class,
            names = {"CUT_SHORT_OF_INFO_HEADER", "TWO_ID3V2_TAGS"},
            mode = EnumSource.Mode.EXCLUDE)
    void decodesEveryFrame(final Mp3Sample sample) throws IOException, InvalidFormatException {
        try (final var channel = sample.channel()) {
            final var audio = new Mp3Parser().parse(channel).audio();
            var samples = 0L;
            for (final PcmFrame frame : audio) {
                samples += frame.sampleCount();
            }

            assertThat(samples).isEqualTo(audio.streamInfo().sampleCount());
        }
    }

    // FFmpeg trims the same samples from the start of a file with a LAME tag. It doesn't trim the padding an MPEG-2
    // file's LAME tag gives from its end, and the conformance tests' reference decoder doesn't give their last frame,
    // so the two may differ by a frame at the end.
    @ParameterizedTest
    @EnumSource(
            value = Mp3Sample.class,
            names = {
                "STEREO_CBR_ID3V24",
                "MONO_VBR_ID3V23",
                "NO_XING_ID3V1",
                "HE_32KHZ",
                "HE_48KHZ",
                "HE_FREE",
                "HECOMMON",
                "SI",
                "SI_BLOCK",
                "SI_HUFF",
                "HE_MODE",
                "MPEG1_SHORT_BLOCKS",
                "MPEG2_SHORT_BLOCKS",
                "MPEG25_SHORT_BLOCKS",
                "MPEG2_INTENSITY_STEREO",
                "SHINE",
                "ISO_TEST01",
                "ISO_TEST04",
                "ISO_TEST22",
                "ISO_TEST24",
                "ISO_TEST30",
                "ISO_TEST31",
                "ISO_TEST33",
                "LAYER2_TABLE_C",
                "LAYER2_TABLE_D",
                "JUNK_BETWEEN_FRAMES",
                "CUT_AT_SECOND_FRAME",
                "CUT_IN_LAST_FRAME",
                "CUT_IN_LAST_FRAME_SIDE_INFO",
                "NOISE_CUT_IN_LAST_FRAME_150",
                "NOISE_CUT_IN_LAST_FRAME_300",
                "CUT_SHORT_OF_INFO_HEADER"
            })
    void decodesAsReferenceDecoderDoes(final Mp3Sample sample) throws IOException, InvalidFormatException {
        final var reference = referenceSamples(sample);
        final var decoded = decode(sample);

        assertThat(decoded.length).isCloseTo(reference.length, within(FRAME_SAMPLES));
        // The audio is loud, so the samples can't match by both being near silence.
        assertThat(Arrays.stream(decoded).max().orElseThrow()).isGreaterThan(500);
        for (var index = 0; index < Math.min(decoded.length, reference.length); index++) {
            assertThat(decoded[index]).as("sample %d", index).isCloseTo(reference[index], within(TOLERANCE));
        }
    }

    // The free-format sample is the fixed-bitrate one with its headers' bitrates cleared, which FFmpeg can't decode.
    @Test
    void decodesFreeFormatAsItsSourceStream() throws IOException, InvalidFormatException {
        assertThat(decode(Mp3Sample.FREE_FORMAT)).containsExactly(decode(Mp3Sample.NO_XING_ID3V1));
    }

    // FFmpeg's decoder skips zero bytes before a frame, so they change nothing. The second frame starts at byte 417,
    // and its padding bit says whether it takes 418 bytes or 417. The first frame must be followed by the second, or
    // FFmpeg's demuxer skips it.
    @Test
    void skipsZerosBetweenFrames() throws IOException, InvalidFormatException {
        final var frames = sampleBytes(Mp3Sample.NO_XING_ID3V1);
        final var third = 417 + 417 + (frames[417 + 2] >> 1 & 1);
        final var bytes = new byte[frames.length + 50];
        System.arraycopy(frames, 0, bytes, 0, third);
        System.arraycopy(frames, third, bytes, third + 50, frames.length - third);

        assertThat(decode(bytes)).containsExactly(decode(Mp3Sample.NO_XING_ID3V1));
    }

    // Three bytes of a header after the last frame make no frame, though the zeros after the file would complete them.
    @Test
    void ignoresHeaderCutShortAfterLastFrame() throws IOException, InvalidFormatException {
        final var frames = sampleBytes(Mp3Sample.NO_XING_ID3V1);
        final var lastFrameEnd = frames.length - 128;
        final var bytes = Arrays.copyOf(frames, lastFrameEnd + 3);
        System.arraycopy(new byte[] {(byte) 0xFF, (byte) 0xFB, (byte) 0x90}, 0, bytes, lastFrameEnd, 3);

        assertThat(decode(bytes)).containsExactly(decode(Mp3Sample.NO_XING_ID3V1));
    }

    // Each frame is given with its own sample rate, as a stream of two joined files may change it.
    @Test
    void givesEachFrameItsOwnSampleRate() throws IOException, InvalidFormatException {
        final var first = sampleBytes(Mp3Sample.HE_32KHZ);
        final var second = sampleBytes(Mp3Sample.HE_48KHZ);
        final var joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);

        try (final var channel = new SeekableInMemoryByteChannel(joined)) {
            final var rates = new TreeSet<Integer>();
            for (final var frame : new Mp3Parser().parse(channel).audio()) {
                rates.add(frame.sampleRate());
            }

            assertThat(rates).containsExactly(32000, 48000);
        }
    }

    // FFmpeg reads every ID3v2 tag a file starts with, so the audio starts after the last.
    @Test
    void decodesAfterEveryId3v2Tag() throws IOException, InvalidFormatException {
        assertThat(decode(Mp3Sample.TWO_ID3V2_TAGS)).containsExactly(decode(Mp3Sample.ID3V22));
    }

    // FFmpeg's parser finds the first frame wherever it is, but drops it, as it follows bytes that aren't a frame.
    @Test
    void findsFirstFrameFarFromStart() throws IOException, InvalidFormatException {
        final var frames = sampleBytes(Mp3Sample.NO_XING_ID3V1);
        final var farBytes = new byte[100_000 + frames.length];
        Arrays.fill(farBytes, 0, 100_000, (byte) 0xA5);
        System.arraycopy(frames, 0, farBytes, 100_000, frames.length);

        final var decoded = decode(farBytes);
        final var expected = decode(Mp3Sample.NO_XING_ID3V1);

        assertThat(decoded.length).isEqualTo(expected.length - FRAME_SAMPLES);
    }

    private static int[] decode(final Mp3Sample sample) throws IOException, InvalidFormatException {
        return decode(sampleBytes(sample));
    }

    // Each frame's samples, with its own channels interleaved.
    private static int[] decode(final byte[] bytes) throws IOException, InvalidFormatException {
        try (final var channel = new SeekableInMemoryByteChannel(bytes)) {
            final var samples = IntStream.builder();
            for (final var frame : new Mp3Parser().parse(channel).audio()) {
                for (var index = 0; index < frame.sampleCount(); index++) {
                    for (var frameChannel = 0; frameChannel < frame.channelCount(); frameChannel++) {
                        samples.add(sixteenBits(frame.channelSample(frameChannel, index)));
                    }
                }
            }
            return samples.build().toArray();
        }
    }

    // As FFmpeg rounds a float sample to 16 bits: to the nearest, half to even, and clipped.
    private static int sixteenBits(final float sample) {
        return (int) Math.clamp(Math.rint(sample * 32768.0), Short.MIN_VALUE, Short.MAX_VALUE);
    }

    private static byte[] sampleBytes(final Mp3Sample sample) throws IOException {
        try (final var inputStream = sample.inputStream()) {
            return inputStream.readAllBytes();
        }
    }

    // Each sample is 16 bits, little-endian and signed.
    private static int[] referenceSamples(final Mp3Sample sample) throws IOException {
        try (final var input = sample.referencePcm()) {
            final var bytes = input.readAllBytes();
            final var bits = BitInputStream.wrap(new ByteArrayInputStream(bytes), ByteOrder.LITTLE_ENDIAN);
            final var samples = new int[bytes.length / Short.BYTES];
            for (var index = 0; index < samples.length; index++) {
                samples[index] = (short) bits.readBits(Short.SIZE);
            }
            return samples;
        }
    }
}
