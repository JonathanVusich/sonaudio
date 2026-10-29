package dev.javax.sonaudio.format.mp3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.javax.bitstream.BitInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import dev.javax.sonaudio.exception.InvalidFormatException;

class FrameHeaderTest {

    @Test
    void readsMpeg1FrameHeader() throws IOException, InvalidFormatException {
        // The first frame of audio in Mp3Sample.STEREO_CBR_ID3V24.
        final var header = readHeader("FFFB9064");

        assertThat(header)
                .isEqualTo(new FrameHeader(
                        MpegVersion.MPEG_1,
                        Layer.III,
                        Protection.NONE,
                        128_000,
                        44100,
                        0,
                        ChannelMode.JOINT_STEREO,
                        2));
        assertThat(header.frameLength()).isEqualTo(417);
        assertThat(header.channelCount()).isEqualTo(2);
        assertThat(header.sideInfoSize()).isEqualTo(32);
    }

    @Test
    void readsPaddedMpeg2MonoFrameHeader() throws IOException, InvalidFormatException {
        // MPEG-2, 56 kbit/s, 22.05 kHz, padded, mono.
        final var header = readHeader("FFF372C0");

        assertThat(header)
                .isEqualTo(new FrameHeader(
                        MpegVersion.MPEG_2, Layer.III, Protection.NONE, 56_000, 22050, 1, ChannelMode.MONO, 0));
        assertThat(header.frameLength()).isEqualTo(72 * 56_000 / 22050 + 1);
        assertThat(header.sideInfoSize()).isEqualTo(9);
    }

    // Bitrate index 9 is 160 kbit/s in MPEG-1's Layer II, whose frames take 144 bytes for each bit per sample.
    @Test
    void readsLayerIIFrameHeader() throws IOException, InvalidFormatException {
        final var header = readHeader("FFFD9064");

        assertThat(header.layer()).isEqualTo(Layer.II);
        assertThat(header.bitrate()).isEqualTo(160_000);
        assertThat(header.samplesPerFrame()).isEqualTo(1152);
        assertThat(header.frameLength()).isEqualTo(144 * 160_000 / 44100);
    }

    // Bitrate index 9 is 288 kbit/s in MPEG-1's Layer I, whose frames take 12 slots of four bytes for each bit per
    // sample.
    @Test
    void readsLayerIFrameHeader() throws IOException, InvalidFormatException {
        final var header = readHeader("FFFF9064");

        assertThat(header.layer()).isEqualTo(Layer.I);
        assertThat(header.bitrate()).isEqualTo(288_000);
        assertThat(header.samplesPerFrame()).isEqualTo(384);
        assertThat(header.frameLength()).isEqualTo(12 * 288_000 / 44100 * 4);
    }

    @Test
    void readsMpeg25FrameHeader() throws IOException, InvalidFormatException {
        final var header = readHeader("FFE38800");

        assertThat(header.version()).isEqualTo(MpegVersion.MPEG_2_5);
        assertThat(header.sampleRate()).isEqualTo(8000);
        assertThat(header.bitrate()).isEqualTo(64_000);
    }

    @Test
    void readsWholeHeader() throws IOException, InvalidFormatException {
        final var bits = bits("FFFB906441");
        new Mp3Parser().readAudioFrameHeader(bits);

        assertThat(bits.readBits(8)).isEqualTo(0x41);
    }

    // A free-format frame's header gives no bitrate, so its stream gives the frame's length.
    @Test
    void readsFreeFormatFrameHeader() throws IOException, InvalidFormatException {
        final var header = readHeader("FFFB0064");

        assertThat(header.bitrate()).isEqualTo(FrameHeader.FREE_FORMAT_BITRATE);
        assertThatThrownBy(header::frameLength).isInstanceOf(IllegalStateException.class);
        assertThat(header.withFreeFormatLength(417).frameLength()).isEqualTo(417);
    }

    // Every length a free-format frame can have, at every sample rate, comes back from the bitrate it's given. Each
    // header is MPEG-1, MPEG-2 or MPEG-2.5, with no CRC, bitrate index 0 and each sample rate index.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "FFFB0000",
                "FFFB0400",
                "FFFB0800",
                "FFF30000",
                "FFF30400",
                "FFF30800",
                "FFE30000",
                "FFE30400",
                "FFE30800"
            })
    void freeFormatBitrateGivesBackItsLength(final String hex) throws IOException, InvalidFormatException {
        final var header = readHeader(hex);
        for (var length = FrameHeader.SIZE; length <= 5761; length++) {
            assertThat(header.withFreeFormatLength(length).frameLength()).isEqualTo(length);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // No frame sync.
                "49443304",
                // The reserved layer.
                "FFF99064",
                // Free format in Layer II.
                "FFFD0064",
                // Bitrate index 15.
                "FFFBF064",
                // Reserved sample rate.
                "FFFB9C64",
                // Reserved MPEG version.
                "FFEB9064"
            })
    void rejectsWhatIsNotAFrameHeader(final String hex) {
        assertThatThrownBy(() -> readHeader(hex)).isInstanceOf(InvalidFormatException.class);
    }

    private static FrameHeader readHeader(final String hex) throws IOException, InvalidFormatException {
        return new Mp3Parser().readAudioFrameHeader(bits(hex));
    }

    private static BitInputStream bits(final String hex) {
        return BitInputStream.wrap(new ByteArrayInputStream(HexFormat.of().parseHex(hex)), ByteOrder.BIG_ENDIAN);
    }
}
