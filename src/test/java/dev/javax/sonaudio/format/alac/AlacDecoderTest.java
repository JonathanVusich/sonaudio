package dev.javax.sonaudio.format.alac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.AudioUtils;
import dev.javax.sonaudio.samples.AlacSample;
import dev.javax.sonaudio.samples.FateAlacCase;
import dev.javax.sonaudio.samples.FateSamples;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.NoSuchElementException;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AlacDecoderTest {

    // The references are FFmpeg's decodes, at each stream's bit depth.
    @ParameterizedTest
    @EnumSource(AlacSample.class)
    void decodesEverySampleToFfmpegsOutput(final AlacSample sample) throws IOException, InvalidFormatException {
        try (final var channel = sample.channel();
                final var reference = sample.referencePcm()) {
            final var audio = new AlacParser().parse(channel).audio();

            final var decoded = AlacPcm.decodeToBytes(audio);

            final var cookie = audio.streamInfo().cookie();
            AlacPcm.assertSamePcm(
                    reference.readAllBytes(), decoded, cookie.channelCount(), cookie.bitsPerSample() / Byte.SIZE);
        }
    }

    @ParameterizedTest
    @EnumSource(AlacSample.class)
    void measuredDurationIsTheHeadersDuration(final AlacSample sample) throws IOException, InvalidFormatException {
        try (final var channel = sample.channel()) {
            final var parsed = new AlacParser().parse(channel);

            final var measured = AudioUtils.measureDuration(parsed.audio());

            assertThat(measured).isEqualTo(parsed.metadata().duration());
            assertThat(measured).isEqualTo(parsed.audio().streamInfo().duration());
        }
    }

    @Test
    void reportsTheStreamsFormat() throws IOException, InvalidFormatException {
        try (final var channel = AlacSample.SURROUND51_16.channel()) {
            final var audio = new AlacParser().parse(channel).audio();

            assertThat(audio.sampleRate()).isEqualTo(44100);
            assertThat(audio.bitsPerSample()).isEqualTo(16);
            assertThat(audio.streamInfo().cookie().channelCount()).isEqualTo(6);
            assertThat(audio.streamInfo().cookie().frameLength()).isEqualTo(4096);
            assertThat(audio.streamInfo().sampleCount()).isEqualTo(8820);
        }
    }

    @Test
    void returnsFramesOfTheStreamsSampleRateAndChannels() throws IOException, InvalidFormatException {
        try (final var channel = AlacSample.STEREO16_LEVEL1.channel()) {
            final var audio = new AlacParser().parse(channel).audio();

            final var first = audio.nextFrame();

            assertThat(first.sampleRate()).isEqualTo(44100);
            assertThat(first.channelCount()).isEqualTo(2);
            assertThat(first.sampleCount()).isEqualTo(4096);
        }
    }

    @Test
    void refusesToGiveAFrameAfterTheLast() throws IOException, InvalidFormatException {
        try (final var channel = AlacSample.MONO16_8KHZ.channel()) {
            final var audio = new AlacParser().parse(channel).audio();
            AlacPcm.decodeToBytes(audio);

            assertThat(audio.hasNextFrame()).isFalse();
            assertThatThrownBy(audio::nextFrame).isInstanceOf(NoSuchElementException.class);
        }
    }

    @Test
    void refusesAFileCutShortInItsAudio() throws IOException, InvalidFormatException {
        final var bytes = AlacTestFiles.bytes(AlacSample.MOOV_BEFORE_MDAT);
        // The moov box comes first, so the audio is at the end.
        final var cut = Arrays.copyOf(bytes, bytes.length - 100);
        final var audio =
                new AlacParser().parse(new SeekableInMemoryByteChannel(cut)).audio();

        assertThatThrownBy(() -> AlacPcm.decodeToBytes(audio)).isInstanceOf(EOFException.class);
    }

    @Test
    void refusesAPacketWithAnElementItDoesNotSupport() throws IOException, InvalidFormatException {
        final var bytes = AlacTestFiles.bytes(AlacSample.STEREO16_LEVEL0);
        // Sets the first element of the first packet to 4, a data stream element.
        final var packetStart = AlacTestFiles.firstPacketOffset(bytes);
        bytes[packetStart] = (byte) (bytes[packetStart] & 0x1F | 0x80);
        final var audio =
                new AlacParser().parse(new SeekableInMemoryByteChannel(bytes)).audio();

        assertThatThrownBy(audio::hasNextFrame)
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("element of type 4");
    }

    // A packet that ends at once leaves its channels undecoded, which FFmpeg doesn't make a frame of, or an error of.
    @Test
    void givesNoFrameForAPacketOfOnlyAnEndTag() throws IOException, InvalidFormatException {
        final var bytes = AlacTestFiles.bytes(AlacSample.STEREO16_LEVEL0);
        final var packetStart = AlacTestFiles.firstPacketOffset(bytes);
        bytes[packetStart] = (byte) (bytes[packetStart] | 0xE0);
        final var audio =
                new AlacParser().parse(new SeekableInMemoryByteChannel(bytes)).audio();

        final var decoded = AlacPcm.decodeToBytes(audio);

        try (final var reference = AlacSample.STEREO16_LEVEL0.referencePcm()) {
            final var firstPacketBytes = 4096 * 2 * 2;
            final var expected = reference.readAllBytes();
            AlacPcm.assertSamePcm(Arrays.copyOfRange(expected, firstPacketBytes, expected.length), decoded, 2, 2);
        }
    }

    @Test
    void decodesInsideM4aToFatesMd5() throws IOException, InvalidFormatException, NoSuchAlgorithmException {
        final var file = FateSamples.insideFile();
        try (final var channel = Files.newByteChannel(file)) {
            final var audio = new AlacParser().parse(channel).audio();

            final var decoded = AlacPcm.decodeToBytes(audio);

            final var md5 = MessageDigest.getInstance("MD5").digest(decoded);
            assertThat(HexFormat.of().formatHex(md5)).isEqualTo(FateSamples.INSIDE_M4A_MD5);
        }
    }

    // FATE decodes the encodes of its source audio, and requires the decodes to be the source's samples exactly.
    @ParameterizedTest
    @EnumSource(FateAlacCase.class)
    void decodesFateEncodesToTheirSource(final FateAlacCase fateCase) throws IOException, InvalidFormatException {
        final var file = FateSamples.alacFile(fateCase);
        final var referenceFile = FateSamples.alacReferencePcm(fateCase);
        try (final var channel = Files.newByteChannel(file)) {
            final var audio = new AlacParser().parse(channel).audio();

            final var decoded = AlacPcm.decodeToBytes(audio);

            final var cookie = audio.streamInfo().cookie();
            AlacPcm.assertSamePcm(
                    Files.readAllBytes(referenceFile),
                    decoded,
                    cookie.channelCount(),
                    cookie.bitsPerSample() / Byte.SIZE);
        }
    }
}
