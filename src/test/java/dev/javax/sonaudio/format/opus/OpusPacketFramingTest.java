package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.samples.FateOpusVector;
import dev.javax.sonaudio.samples.FateSamples;
import dev.javax.sonaudio.samples.OpusSample;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class OpusPacketFramingTest {

    // RFC 6716 section 3.1, table 2: the modes, bandwidths and frame sizes (at 48 kHz) of the 32 configurations.
    private static final OpusMode[] MODES = new OpusMode[32];
    private static final OpusBandwidth[] BANDWIDTHS = new OpusBandwidth[32];
    private static final int[] FRAME_SAMPLES = {
        480, 960, 1920, 2880, 480, 960, 1920, 2880, 480, 960, 1920, 2880, 480, 960, 480, 960, 120, 240, 480, 960, 120,
        240, 480, 960, 120, 240, 480, 960, 120, 240, 480, 960
    };

    static {
        for (var config = 0; config < 32; config++) {
            MODES[config] = modeOf(config);
            BANDWIDTHS[config] = bandwidthOf(config);
        }
    }

    private final OpusParser parser = new OpusParser();

    private static OpusMode modeOf(final int config) {
        if (config < 12) {
            return OpusMode.SILK;
        }
        if (config < 16) {
            return OpusMode.HYBRID;
        }
        return OpusMode.CELT;
    }

    private static OpusBandwidth bandwidthOf(final int config) {
        final var silk = List.of(OpusBandwidth.NARROWBAND, OpusBandwidth.MEDIUMBAND, OpusBandwidth.WIDEBAND);
        if (config < 12) {
            return silk.get(config / 4);
        }
        if (config < 14) {
            return OpusBandwidth.SUPERWIDEBAND;
        }
        if (config < 16) {
            return OpusBandwidth.FULLBAND;
        }
        final var celt = List.of(
                OpusBandwidth.NARROWBAND, OpusBandwidth.WIDEBAND, OpusBandwidth.SUPERWIDEBAND, OpusBandwidth.FULLBAND);
        return celt.get((config - 16) / 4);
    }

    @ParameterizedTest
    @ValueSource(
            ints = {
                0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27,
                28, 29, 30, 31
            })
    void readsTheModeBandwidthAndFrameSizeOfEveryConfiguration(final int config) throws InvalidFormatException {
        final var packet = framing(config << 3, 1, 2, 3);

        assertThat(packet.mode()).isEqualTo(MODES[config]);
        assertThat(packet.bandwidth()).isEqualTo(BANDWIDTHS[config]);
        assertThat(packet.frameSamples()).isEqualTo(FRAME_SAMPLES[config]);
    }

    @Test
    void readsTheStereoFlag() throws InvalidFormatException {
        assertThat(framing(0b0000_0000, 1).coding()).isEqualTo(ChannelCoding.MONO);
        assertThat(framing(0b0000_0100, 1).coding()).isEqualTo(ChannelCoding.STEREO);
    }

    @Test
    void framesOneFrameOfCodeZero() throws InvalidFormatException {
        final var packet = framing(0x08, 1, 2, 3, 4);

        assertThat(packet.frameCount()).isEqualTo(1);
        assertThat(packet.frameOffset(0)).isEqualTo(1);
        assertThat(packet.frameSize(0)).isEqualTo(4);
        assertThat(packet.packetSize()).isEqualTo(5);
        assertThat(packet.sampleCount()).isEqualTo(960);
        assertThat(packet.frameMillis()).isEqualTo(20);
    }

    @Test
    void acceptsAFrameOfNoBytes() throws InvalidFormatException {
        final var packet = framing(0x08);

        assertThat(packet.frameSize(0)).isZero();
    }

    @Test
    void framesTwoEqualFramesOfCodeOne() throws InvalidFormatException {
        final var packet = framing(0x09, 1, 2, 3, 4);

        assertThat(packet.frameCount()).isEqualTo(2);
        assertThat(packet.frameOffset(0)).isEqualTo(1);
        assertThat(packet.frameSize(0)).isEqualTo(2);
        assertThat(packet.frameOffset(1)).isEqualTo(3);
        assertThat(packet.frameSize(1)).isEqualTo(2);
        assertThat(packet.sampleCount()).isEqualTo(1920);
    }

    @Test
    void framesTwoFramesOfDifferentSizesOfCodeTwo() throws InvalidFormatException {
        final var packet = framing(0x0A, 3, 1, 2, 3, 4, 5);

        assertThat(packet.frameCount()).isEqualTo(2);
        assertThat(packet.frameOffset(0)).isEqualTo(2);
        assertThat(packet.frameSize(0)).isEqualTo(3);
        assertThat(packet.frameOffset(1)).isEqualTo(5);
        assertThat(packet.frameSize(1)).isEqualTo(2);
    }

    @Test
    void readsAFrameSizeOfTwoBytes() throws InvalidFormatException {
        // 252 and over take a second byte worth four each: 252 + 4 * 1 = 256.
        final var bytes = new int[1 + 2 + 256 + 4];
        bytes[0] = 0x0A;
        bytes[1] = 252;
        bytes[2] = 1;

        final var packet = framing(bytes);

        assertThat(packet.frameSize(0)).isEqualTo(256);
        assertThat(packet.frameSize(1)).isEqualTo(4);
        assertThat(packet.frameOffset(0)).isEqualTo(3);
    }

    @Test
    void framesConstantBitrateFramesOfCodeThree() throws InvalidFormatException {
        final var packet = framing(0x0B, 3, 1, 2, 3, 4, 5, 6);

        assertThat(packet.frameCount()).isEqualTo(3);
        assertThat(packet.frameOffset(0)).isEqualTo(2);
        assertThat(packet.frameSize(0)).isEqualTo(2);
        assertThat(packet.frameOffset(2)).isEqualTo(6);
        assertThat(packet.frameSize(2)).isEqualTo(2);
    }

    @Test
    void framesVariableBitrateFramesOfCodeThree() throws InvalidFormatException {
        final var packet = framing(0x0B, 0x83, 2, 3, 1, 2, 3, 4, 5, 6, 7, 8);

        assertThat(packet.frameCount()).isEqualTo(3);
        assertThat(packet.frameOffset(0)).isEqualTo(4);
        assertThat(packet.frameSize(0)).isEqualTo(2);
        assertThat(packet.frameOffset(1)).isEqualTo(6);
        assertThat(packet.frameSize(1)).isEqualTo(3);
        assertThat(packet.frameOffset(2)).isEqualTo(9);
        assertThat(packet.frameSize(2)).isEqualTo(3);
    }

    @Test
    void takesPaddingOffTheEndOfTheLastFrame() throws InvalidFormatException {
        // One frame, with padding: 3 bytes of it, after 5 of the frame.
        final var packet = framing(0x0B, 0x41, 3, 1, 2, 3, 4, 5, 0, 0, 0);

        assertThat(packet.frameCount()).isEqualTo(1);
        assertThat(packet.frameSize(0)).isEqualTo(5);
        assertThat(packet.packetSize()).isEqualTo(11);
    }

    @Test
    void readsPaddingOfSeveralBytes() throws InvalidFormatException {
        // 255 means 254 and a byte more, so 255 then 2 is 256.
        final var bytes = new int[1 + 1 + 2 + 5 + 256];
        bytes[0] = 0x0B;
        bytes[1] = 0x41;
        bytes[2] = 255;
        bytes[3] = 2;

        final var packet = framing(bytes);

        assertThat(packet.frameSize(0)).isEqualTo(5);
        assertThat(packet.packetSize()).isEqualTo(bytes.length);
    }

    @Test
    void takesTheEndOfASelfDelimitedFrameFromItsLength() throws InvalidFormatException {
        final var delimited = delimitedFraming(0x08, 3, 1, 2, 3, 9, 9, 9);
        final var other = delimitedFraming(0x09, 2, 1, 2, 3, 4, 9, 9);

        assertThat(delimited.frameOffset(0)).isEqualTo(2);
        assertThat(delimited.frameSize(0)).isEqualTo(3);
        assertThat(delimited.packetSize()).isEqualTo(5);
        assertThat(other.frameSize(1)).isEqualTo(2);
        assertThat(other.packetSize()).isEqualTo(6);
    }

    @Test
    void takesTheEndOfSelfDelimitedFramesOfCodeTwoAndThree() throws InvalidFormatException {
        final var code2 = delimitedFraming(0x0A, 3, 2, 1, 2, 3, 4, 5, 9, 9);
        final var cbr = delimitedFraming(0x0B, 2, 4, 1, 2, 3, 4, 5, 6, 7, 8, 9, 9);
        final var vbr = delimitedFraming(0x0B, 0x82, 3, 2, 1, 2, 3, 4, 5, 9, 9);

        assertThat(code2.frameSize(0)).isEqualTo(3);
        assertThat(code2.frameSize(1)).isEqualTo(2);
        assertThat(code2.packetSize()).isEqualTo(8);
        assertThat(cbr.frameSize(1)).isEqualTo(4);
        assertThat(cbr.packetSize()).isEqualTo(11);
        assertThat(vbr.frameSize(0)).isEqualTo(3);
        assertThat(vbr.frameSize(1)).isEqualTo(2);
        assertThat(vbr.packetSize()).isEqualTo(9);
    }

    @Test
    void rejectsAnEmptyPacket() {
        // RFC 6716 requirement R1: a packet has at least one byte.
        assertThatThrownBy(() -> parser.parsePacket(new byte[0], 0, 0, Delimiting.UNDELIMITED))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void rejectsAFrameOfMoreThan1275Bytes() throws InvalidFormatException {
        // R2: no frame is larger than 1275 bytes.
        final var big = new int[1 + 1276];
        big[0] = 0x08;
        final var limit = new int[1 + 1275];
        limit[0] = 0x08;

        assertThatThrownBy(() -> framing(big)).isInstanceOf(InvalidFormatException.class);
        assertThat(framing(limit).frameSize(0)).isEqualTo(1275);
    }

    @Test
    void rejectsTwoFramesOfCodeOneThatAreNotTheSameSize() {
        // R3: the two frames of code 1 are the same size, so the packet's length after the TOC byte is even.
        assertThatThrownBy(() -> framing(0x09, 1, 2, 3)).isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void rejectsCodeTwoWithoutALengthOrWithAFrameLongerThanThePacket() {
        // R4: a packet of code 2 has a length after the TOC byte that doesn't go past the end.
        assertThatThrownBy(() -> framing(0x0A)).isInstanceOf(InvalidFormatException.class);
        assertThatThrownBy(() -> framing(0x0A, 9, 1, 2, 3)).isInstanceOf(InvalidFormatException.class);
        assertThatThrownBy(() -> framing(0x0A, 252)).isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void rejectsCodeThreeWithoutAFrameCountOrWithNoFrames() {
        // R5: a packet of code 3 has a frame count byte, from 1 frame.
        assertThatThrownBy(() -> framing(0x0B)).isInstanceOf(InvalidFormatException.class);
        assertThatThrownBy(() -> framing(0x0B, 0)).isInstanceOf(InvalidFormatException.class);
        assertThatThrownBy(() -> framing(0x0B, 49, 1, 2)).isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void rejectsAPacketOfMoreThan120Milliseconds() throws InvalidFormatException {
        // R5 again: 6 frames of 20 ms are 120 ms, and 7 are too long.
        final var six = new int[2 + 12];
        six[0] = 0x0B;
        six[1] = 6;
        final var seven = new int[2 + 14];
        seven[0] = 0x0B;
        seven[1] = 7;

        assertThat(framing(six).frameCount()).isEqualTo(6);
        assertThatThrownBy(() -> framing(seven)).isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void rejectsConstantBitrateFramesThatDoNotDivideThePacket() {
        // R6: in a packet of code 3 without variable bitrate, the frames all have the same size.
        assertThatThrownBy(() -> framing(0x0B, 3, 1, 2, 3, 4)).isInstanceOf(InvalidFormatException.class);
        assertThatThrownBy(() -> framing(0x0B, 0x41, 9, 1, 2)).isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void rejectsVariableBitrateFramesLongerThanThePacket() {
        // R7: the frame lengths and the padding of a packet of code 3 fit in it.
        assertThatThrownBy(() -> framing(0x0B, 0x83, 50, 3, 1, 2, 3)).isInstanceOf(InvalidFormatException.class);
        assertThatThrownBy(() -> framing(0x0B, 0x83, 2)).isInstanceOf(InvalidFormatException.class);
        assertThatThrownBy(() -> framing(0x0B, 0xC2, 5, 1, 2, 3)).isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void rejectsSelfDelimitedLengthsThatGoPastThePacket() {
        assertThatThrownBy(() -> delimitedFraming(0x08, 9, 1, 2)).isInstanceOf(InvalidFormatException.class);
        assertThatThrownBy(() -> delimitedFraming(0x09, 3, 1, 2, 3)).isInstanceOf(InvalidFormatException.class);
        assertThatThrownBy(() -> delimitedFraming(0x08)).isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void readsFromAnOffsetOfTheBytes() throws InvalidFormatException {
        final var bytes = new byte[] {9, 9, 0x09, 1, 2, 3, 4, 7};

        final var packet = parser.parsePacket(bytes, 2, 5, Delimiting.UNDELIMITED);

        assertThat(packet.frameOffset(0)).isEqualTo(1);
        assertThat(packet.packetSize()).isEqualTo(5);
    }

    @ParameterizedTest
    @EnumSource(OpusSample.class)
    void framesEveryPacketOfASampleAsItsLabelSays(final OpusSample sample) throws IOException, InvalidFormatException {
        final var expected = expectedFraming(sample);
        final var file = sampleBytes(sample);

        final var packets = OggTestFiles.audioPackets(file);

        assertThat(packets).isNotEmpty();
        for (final var packet : packets) {
            final var framing = parser.parsePacket(packet.bytes(), 0, packet.bytes().length, firstDelimiting(sample));
            assertThat(framing.mode()).as("%s mode", sample).isEqualTo(expected.mode());
            assertThat(framing.bandwidth()).as("%s bandwidth", sample).isEqualTo(expected.bandwidth());
            assertThat(framing.frameSamples()).as("%s frame size", sample).isEqualTo(expected.frameSamples());
        }
    }

    @ParameterizedTest
    @EnumSource(FateOpusVector.class)
    void framesEveryPacketOfEveryFateVector(final FateOpusVector vector) throws IOException, InvalidFormatException {
        final var file = Files.readAllBytes(FateSamples.opusFile(vector));
        final var info =
                parser.parse(new SeekableInMemoryByteChannel(file)).audio().streamInfo();

        final var packets = OggTestFiles.audioPackets(file);

        assertThat(packets).isNotEmpty();
        for (final var packet : packets) {
            assertThat(framesEveryStream(packet.bytes(), info.streamCount())).isTrue();
        }
    }

    // Every stream's part of a packet frames, one after another, and all hold the same number of samples.
    private boolean framesEveryStream(final byte[] bytes, final int streamCount) throws InvalidFormatException {
        var offset = 0;
        var samples = -1;
        for (var stream = 0; stream < streamCount; stream++) {
            var delimiting = Delimiting.SELF_DELIMITED;
            if (stream == streamCount - 1) {
                delimiting = Delimiting.UNDELIMITED;
            }
            final var framing = parser.parsePacket(bytes, offset, bytes.length - offset, delimiting);
            if (samples >= 0 && framing.sampleCount() != samples) {
                return false;
            }
            samples = framing.sampleCount();
            offset += framing.packetSize();
        }
        return offset == bytes.length;
    }

    private record ExpectedFraming(OpusMode mode, OpusBandwidth bandwidth, int frameSamples) {}

    // What reference/inspect-opus.py read from the TOC bytes of each sample, as OpusSample's comments record it.
    private ExpectedFraming expectedFraming(final OpusSample sample) {
        return switch (sample) {
            case SILK_NB_MONO_20MS, SILK_NB_STEREO_20MS -> silk(OpusBandwidth.NARROWBAND, 960);
            case SILK_MB_MONO_20MS, SILK_MB_STEREO_20MS -> silk(OpusBandwidth.MEDIUMBAND, 960);
            case SILK_WB_MONO_20MS, SILK_WB_STEREO_20MS, OUTPUT_GAIN, TAGGED, R128_PICTURE ->
                silk(OpusBandwidth.WIDEBAND, 960);
            case SILK_NB_MONO_10MS -> silk(OpusBandwidth.NARROWBAND, 480);
            case SILK_NB_MONO_40MS -> silk(OpusBandwidth.NARROWBAND, 1920);
            case SILK_NB_MONO_60MS -> silk(OpusBandwidth.NARROWBAND, 2880);
            case SILK_WB_STEREO_10MS -> silk(OpusBandwidth.WIDEBAND, 480);
            case SILK_WB_STEREO_40MS -> silk(OpusBandwidth.WIDEBAND, 1920);
            case SILK_WB_STEREO_60MS -> silk(OpusBandwidth.WIDEBAND, 2880);
            case HYBRID_SWB_MONO_10MS -> hybrid(OpusBandwidth.SUPERWIDEBAND, 480);
            case HYBRID_SWB_MONO_20MS, HYBRID_SWB_STEREO_20MS, MULTIFRAME_HYBRID_120MS, MULTIFRAME_HYBRID_40MS ->
                hybrid(OpusBandwidth.SUPERWIDEBAND, 960);
            case HYBRID_FB_MONO_10MS -> hybrid(OpusBandwidth.FULLBAND, 480);
            case HYBRID_FB_MONO_20MS, HYBRID_FB_STEREO_20MS -> hybrid(OpusBandwidth.FULLBAND, 960);
            case CELT_LOWDELAY_MONO_2_5MS, CELT_LOWDELAY_STEREO_2_5MS -> celt(120);
            case CELT_LOWDELAY_MONO_5MS, CELT_AUDIO_MONO_5MS -> celt(240);
            case CELT_LOWDELAY_MONO_10MS -> celt(480);
            case CELT_LOWDELAY_MONO_20MS,
                    CELT_LOWDELAY_STEREO_20MS,
                    CELT_AUDIO_MONO_20MS,
                    CELT_AUDIO_STEREO_20MS,
                    MULTIFRAME_CELT_40MS_CBR,
                    MULTIFRAME_CELT_40MS_VBR,
                    MULTIFRAME_CELT_120MS,
                    SURROUND51 -> celt(960);
            case MULTIFRAME_SILK_80MS -> silk(OpusBandwidth.WIDEBAND, 1920);
        };
    }

    private ExpectedFraming silk(final OpusBandwidth bandwidth, final int frameSamples) {
        return new ExpectedFraming(OpusMode.SILK, bandwidth, frameSamples);
    }

    private ExpectedFraming hybrid(final OpusBandwidth bandwidth, final int frameSamples) {
        return new ExpectedFraming(OpusMode.HYBRID, bandwidth, frameSamples);
    }

    private ExpectedFraming celt(final int frameSamples) {
        return new ExpectedFraming(OpusMode.CELT, OpusBandwidth.FULLBAND, frameSamples);
    }

    // The 5.1 sample has four streams, so its first is followed by others.
    private Delimiting firstDelimiting(final OpusSample sample) {
        if (sample == OpusSample.SURROUND51) {
            return Delimiting.SELF_DELIMITED;
        }
        return Delimiting.UNDELIMITED;
    }

    private byte[] sampleBytes(final OpusSample sample) throws IOException {
        try (final var inputStream = sample.inputStream()) {
            return inputStream.readAllBytes();
        }
    }

    private OpusPacket framing(final int... values) throws InvalidFormatException {
        final var bytes = bytes(values);
        return parser.parsePacket(bytes, 0, bytes.length, Delimiting.UNDELIMITED);
    }

    private OpusPacket delimitedFraming(final int... values) throws InvalidFormatException {
        final var bytes = bytes(values);
        return parser.parsePacket(bytes, 0, bytes.length, Delimiting.SELF_DELIMITED);
    }

    private byte[] bytes(final int[] values) {
        final var bytes = new byte[values.length];
        for (var index = 0; index < values.length; index++) {
            bytes[index] = (byte) values[index];
        }
        return bytes;
    }
}
