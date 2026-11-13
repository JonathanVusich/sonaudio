package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.Format;
import dev.javax.sonaudio.metadata.Tag;
import dev.javax.sonaudio.metadata.TagSet;
import dev.javax.sonaudio.metadata.TagValue;
import dev.javax.sonaudio.samples.FingerprintAudioSample;
import dev.javax.sonaudio.samples.OpusSample;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OpusParserTest {

    private static final int OPUS_HEAD_FIRST_PAGE = 0;
    private static final int SAMPLE_RATE = 48000;

    private final OpusParser parser = new OpusParser();

    @Test
    void readsTheFormat() {
        assertThat(parser.format()).isEqualTo(Format.OPUS);
    }

    @Test
    void readsTagsLikeFlacDoes() throws IOException, InvalidFormatException {
        final var metadata = parser.parse(OpusSample.TAGGED.channel()).metadata();

        assertThat(metadata.findValues(Tag.TRACK_TITLE)).containsExactly(new TagValue.Str("Test Title"));
        assertThat(metadata.findValues(Tag.TRACK_ARTIST)).containsExactly(new TagValue.Str("Test Artist"));
        assertThat(metadata.findValues(Tag.ALBUM_ARTIST)).containsExactly(new TagValue.Str("Test Album Artist"));
        assertThat(metadata.findValues(Tag.RELEASE_TITLE)).containsExactly(new TagValue.Str("Test Album"));
        assertThat(metadata.findValues(Tag.COMPOSER)).containsExactly(new TagValue.Str("Test Composer"));
        assertThat(metadata.findValues(Tag.TRACK_NUMBER)).containsExactly(new TagValue.Int(3));
        assertThat(metadata.findValues(Tag.DISC_NUMBER)).containsExactly(new TagValue.Int(1));
        final var unknown = unknownTags(OpusSample.TAGGED);
        assertThat(unknown)
                .containsEntry("musicbrainz_trackid", "0c9a6f2e-4f7d-4a3b-9f55-5d3a3c1b7e01")
                .containsEntry("date", "2020-05-06")
                .containsEntry("totaltracks", "12")
                .containsEntry("releasecountry", "XW");
    }

    @Test
    void readsR128AndPictureFieldsAsOrdinaryFields() throws IOException, InvalidFormatException {
        final var unknown = unknownTags(OpusSample.R128_PICTURE);

        assertThat(unknown)
                .containsEntry("r128_track_gain", "-1536")
                .containsEntry("r128_album_gain", "512")
                .containsEntry("replaygain_track_gain", "-6.00 dB")
                .containsKey("metadata_block_picture");
        assertThat(unknown.get("metadata_block_picture")).startsWith("AAAAAw");
    }

    @Test
    void readsTheOutputGainOfTheHeader() throws IOException, InvalidFormatException {
        final var info = parser.parse(OpusSample.OUTPUT_GAIN.channel()).audio().streamInfo();

        assertThat(info.outputGain()).isEqualTo(-768);
        assertThat(info.outputGainDecibels()).isEqualTo(-3.0);
        assertThat(info.preSkip()).isEqualTo(312);
        assertThat(info.inputSampleRate()).isEqualTo(SAMPLE_RATE);
        assertThat(info.channelCount()).isEqualTo(1);
        assertThat(info.mappingFamily()).isEqualTo(ChannelMappingFamily.SINGLE_STREAM);
    }

    @Test
    void readsTheChannelMappingOfSurroundSound() throws IOException, InvalidFormatException {
        final var info = parser.parse(OpusSample.SURROUND51.channel()).audio().streamInfo();

        assertThat(info.channelCount()).isEqualTo(6);
        assertThat(info.mappingFamily()).isEqualTo(ChannelMappingFamily.VORBIS_ORDER);
        assertThat(info.streamCount()).isEqualTo(4);
        assertThat(info.coupledStreamCount()).isEqualTo(2);
        assertThat(info.channelMapping()).containsExactly(0, 4, 1, 2, 3, 5);
        assertThat(info.outputGain()).isZero();
    }

    @Test
    void impliesTheChannelMappingOfMonoAndStereoFiles() throws IOException, InvalidFormatException {
        final var mono =
                parser.parse(OpusSample.SILK_WB_MONO_20MS.channel()).audio().streamInfo();
        final var stereo =
                parser.parse(OpusSample.SILK_WB_STEREO_20MS.channel()).audio().streamInfo();

        assertThat(mono.streamCount()).isEqualTo(1);
        assertThat(mono.coupledStreamCount()).isZero();
        assertThat(stereo.streamCount()).isEqualTo(1);
        assertThat(stereo.coupledStreamCount()).isEqualTo(1);
        assertThat(stereo.channelMapping()).containsExactly(0, 1);
    }

    @ParameterizedTest
    @EnumSource(OpusSample.class)
    void measuresTheDurationFromTheLastGranulePosition(final OpusSample sample)
            throws IOException, InvalidFormatException {
        final var parsed = parser.parse(sample.channel());
        final var referenceSamples = sample.referencePcm().readAllBytes().length
                / 2
                / parsed.audio().streamInfo().channelCount();

        assertThat(parsed.audio().streamInfo().sampleCount()).isEqualTo(referenceSamples);
        assertThat(parsed.metadata().duration())
                .isEqualTo(Duration.ofNanos(referenceSamples * 1_000_000_000L / SAMPLE_RATE));
    }

    @Test
    void decodesAt48KilohertzWith16Bits() throws IOException, InvalidFormatException {
        final var audio = parser.parse(OpusSample.SILK_WB_MONO_20MS.channel()).audio();

        assertThat(audio.sampleRate()).isEqualTo(SAMPLE_RATE);
        assertThat(audio.bitsPerSample()).isEqualTo(16);
    }

    @Test
    void measuresTheDurationOfFilesTheFingerprintTestsUse() throws IOException, InvalidFormatException {
        final var parsed = parser.parse(FingerprintAudioSample.OPUS_CHORDS_48000HZ_STEREO.channel());

        assertThat(parsed.metadata().duration()).isBetween(Duration.ofMillis(24_900), Duration.ofMillis(25_100));
    }

    @Test
    void rejectsAFileThatIsNotOgg() {
        final var notOgg = "RIFF not an Ogg file at all, not even close".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(notOgg)))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void rejectsAnOggFileWithoutOpus() throws IOException {
        final var vorbis = OggTestFiles.packetPage(
                OggTestFiles.BEGINNING, 0, 1, 0, "\u0001vorbis not opus".getBytes(StandardCharsets.US_ASCII));

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(vorbis)))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("No Opus stream");
    }

    @Test
    void rejectsACorruptCrc() throws IOException {
        final var file = fileOf(OpusSample.SILK_WB_MONO_20MS);
        // The first byte of the OpusHead's version.
        file[OggTestFiles.HEADER_SIZE + 1 + 8] ^= 1;

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(file)))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("CRC");
    }

    @Test
    void rejectsACorruptCrcOfTheTagsPage() throws IOException, InvalidFormatException {
        final var file = fileOf(OpusSample.TAGGED);
        final var tagsPage = OggTestFiles.pageOffsets(file).get(1);
        file[OggTestFiles.bodyOffset(file, tagsPage) + 12] ^= 0x40;

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(file)))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("CRC");
    }

    @Test
    void rejectsACorruptAudioPageWhenItIsDecoded() throws IOException, InvalidFormatException {
        final var file = fileOf(OpusSample.SILK_WB_MONO_20MS);
        final var offsets = OggTestFiles.pageOffsets(file);
        file[OggTestFiles.bodyOffset(file, offsets.get(2)) + 3] ^= 0x10;
        final var audio = parser.parse(new SeekableInMemoryByteChannel(file)).audio();

        assertThatThrownBy(() -> {
                    while (audio.hasNextFrame()) {
                        audio.nextFrame();
                    }
                })
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void rejectsAFileCutShortInTheFirstPage() throws IOException {
        final var file = fileOf(OpusSample.SILK_WB_MONO_20MS);
        final var cut = Arrays.copyOf(file, OggTestFiles.HEADER_SIZE + 10);

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(cut)))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("cut short");
    }

    @Test
    void rejectsAFileCutShortInTheTagsPage() throws IOException, InvalidFormatException {
        final var file = fileOf(OpusSample.TAGGED);
        final var tagsPage = OggTestFiles.pageOffsets(file).get(1);
        final var cut = Arrays.copyOf(file, tagsPage + OggTestFiles.HEADER_SIZE + 20);

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(cut)))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("cut short");
    }

    @Test
    void rejectsAFileCutShortInItsLastPageWhenItIsDecoded() throws IOException, InvalidFormatException {
        final var file = fileOf(OpusSample.SILK_WB_MONO_20MS);
        final var cut = Arrays.copyOf(file, file.length - 5);
        final var audio = parser.parse(new SeekableInMemoryByteChannel(cut)).audio();

        assertThatThrownBy(() -> {
                    while (audio.hasNextFrame()) {
                        audio.nextFrame();
                    }
                })
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("cut short");
    }

    @Test
    void readsNoFramesFromAFileWithoutAudio() throws IOException, InvalidFormatException {
        final var file = fileOf(OpusSample.SILK_WB_MONO_20MS);
        final var headersOnly =
                Arrays.copyOf(file, OggTestFiles.pageOffsets(file).get(2));

        final var audio =
                parser.parse(new SeekableInMemoryByteChannel(headersOnly)).audio();

        assertThat(audio.hasNextFrame()).isFalse();
    }

    @Test
    void rejectsAFileWithOnlyTheFirstHeader() throws IOException, InvalidFormatException {
        final var file = fileOf(OpusSample.SILK_WB_MONO_20MS);
        final var headOnly = Arrays.copyOf(file, OggTestFiles.pageOffsets(file).get(1));

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(headOnly)))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("headers");
    }

    @Test
    void rejectsAHeaderOfAnotherMajorVersion() throws IOException, InvalidFormatException {
        final var file = withChangedHead(OpusSample.SILK_WB_MONO_20MS, head -> head[8] = 0x10);

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(file)))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("version");
    }

    @Test
    void acceptsAHeaderOfAnotherMinorVersion() throws IOException, InvalidFormatException {
        final var file = withChangedHead(OpusSample.SILK_WB_MONO_20MS, head -> head[8] = 0x0F);

        final var info =
                parser.parse(new SeekableInMemoryByteChannel(file)).audio().streamInfo();

        assertThat(info.version()).isEqualTo(15);
    }

    @Test
    void rejectsAHeaderWithoutChannels() throws IOException, InvalidFormatException {
        final var file = withChangedHead(OpusSample.SILK_WB_MONO_20MS, head -> head[9] = 0);

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(file)))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("no channels");
    }

    @Test
    void rejectsAHeaderCutShort() throws IOException, InvalidFormatException {
        final var file = fileOf(OpusSample.SILK_WB_MONO_20MS);
        final var offsets = OggTestFiles.pageOffsets(file);
        final var head = Arrays.copyOfRange(file, OggTestFiles.HEADER_SIZE + 1, OggTestFiles.HEADER_SIZE + 1 + 18);
        final var shortHeadFile = OggTestFiles.concatenate(
                OggTestFiles.packetPage(OggTestFiles.BEGINNING, 0, serialOf(file), 0, head),
                Arrays.copyOfRange(file, offsets.get(1), file.length));

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(shortHeadFile)))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("18 bytes");
    }

    @Test
    void rejectsAnUnknownChannelMappingFamily() throws IOException, InvalidFormatException {
        final var file = withChangedHead(OpusSample.SILK_WB_MONO_20MS, head -> head[18] = 7);

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(file)))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("family");
    }

    @Test
    void rejectsFamilyZeroWithMoreThanTwoChannels() throws IOException, InvalidFormatException {
        final var file = withChangedHead(OpusSample.SILK_WB_MONO_20MS, head -> head[9] = 3);

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(file)))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("family 0");
    }

    @Test
    void rejectsAMappingToAStreamThatDoesNotExist() throws IOException, InvalidFormatException {
        final var file = withChangedHead(OpusSample.SURROUND51, head -> head[21 + 1] = 9);

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(file)))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("doesn't exist");
    }

    @Test
    void rejectsMoreCoupledStreamsThanStreams() throws IOException, InvalidFormatException {
        final var file = withChangedHead(OpusSample.SURROUND51, head -> head[20] = 5);

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(file)))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("stream counts");
    }

    @Test
    void rejectsAChannelMappingTableCutShort() throws IOException, InvalidFormatException {
        final var file = fileOf(OpusSample.SURROUND51);
        final var offsets = OggTestFiles.pageOffsets(file);
        final var head = Arrays.copyOfRange(file, OggTestFiles.HEADER_SIZE + 1, OggTestFiles.HEADER_SIZE + 1 + 24);
        final var shortTableFile = OggTestFiles.concatenate(
                OggTestFiles.packetPage(OggTestFiles.BEGINNING, 0, serialOf(file), 0, head),
                Arrays.copyOfRange(file, offsets.get(1), file.length));

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(shortTableFile)))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("table");
    }

    @Test
    void rejectsASecondPacketThatIsNotOpusTags() throws IOException, InvalidFormatException {
        final var file = fileOf(OpusSample.SILK_WB_MONO_20MS);
        final var offsets = OggTestFiles.pageOffsets(file);
        file[OggTestFiles.bodyOffset(file, offsets.get(1)) + 4] = 'x';
        OggTestFiles.fixCrc(file, offsets.get(1));

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(file)))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("OpusTags");
    }

    @Test
    void rejectsTagsWhoseLengthsGoPastThePacket() throws IOException, InvalidFormatException {
        final var file = fileOf(OpusSample.SILK_WB_MONO_20MS);
        final var offsets = OggTestFiles.pageOffsets(file);
        // The vendor string's length, in the byte after the magic, is more than the packet has.
        file[OggTestFiles.bodyOffset(file, offsets.get(1)) + 8 + 1] = 0x7F;
        OggTestFiles.fixCrc(file, offsets.get(1));

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(file)))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void rejectsAudioThatSharesAPageWithTheTags() throws IOException, InvalidFormatException {
        final var file = fileOf(OpusSample.SILK_WB_MONO_20MS);
        final var serial = serialOf(file);
        final var shared = OggTestFiles.concatenate(
                OggTestFiles.packetPage(OggTestFiles.BEGINNING, 0, serial, 0, OggTestFiles.opusHead(1, 312, 0)),
                OggTestFiles.packetPage(0, 0, serial, 1, OggTestFiles.opusTags(), OggTestFiles.audioPacket(1, 5)));

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(shared)))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("page of its own");
    }

    @Test
    void decodesTheFirstOpusStreamOfAMultiplexedFile() throws IOException, InvalidFormatException {
        final var file = fileOf(OpusSample.SILK_WB_MONO_20MS);
        final var offsets = OggTestFiles.pageOffsets(file);
        final var multiplexed = OggTestFiles.concatenate(
                OggTestFiles.packetPage(OggTestFiles.BEGINNING, 0, 4242, 0, new byte[] {1, 2, 3}),
                Arrays.copyOfRange(file, OPUS_HEAD_FIRST_PAGE, offsets.get(2)),
                OggTestFiles.packetPage(0, 5, 4242, 1, new byte[] {4}),
                Arrays.copyOfRange(file, offsets.get(2), file.length));

        final var audio =
                parser.parse(new SeekableInMemoryByteChannel(multiplexed)).audio();
        var samples = 0L;
        while (audio.hasNextFrame()) {
            samples += audio.nextFrame().sampleCount();
        }

        assertThat(samples).isEqualTo(11520);
    }

    private byte[] fileOf(final OpusSample sample) throws IOException {
        try (final var inputStream = sample.inputStream()) {
            return inputStream.readAllBytes();
        }
    }

    private int serialOf(final byte[] file) {
        return Integer.reverseBytes(ByteBuffer.wrap(file, 14, 4).getInt());
    }

    // Changes bytes of the OpusHead, which are numbered from its magic, and writes the page's CRC again.
    private byte[] withChangedHead(final OpusSample sample, final Consumer<byte[]> change)
            throws IOException, InvalidFormatException {
        final var file = fileOf(sample);
        final var head =
                Arrays.copyOfRange(file, OggTestFiles.HEADER_SIZE + 1, OggTestFiles.HEADER_SIZE + 1 + headSize(file));
        change.accept(head);
        System.arraycopy(head, 0, file, OggTestFiles.HEADER_SIZE + 1, head.length);
        OggTestFiles.fixCrc(file, 0);
        return file;
    }

    private int headSize(final byte[] file) {
        return file[OggTestFiles.HEADER_SIZE] & 0xFF;
    }

    private Map<String, String> unknownTags(final OpusSample sample) throws IOException, InvalidFormatException {
        final var metadata = parser.parse(sample.channel()).metadata();
        return metadata.unknownTags().collect(Collectors.toMap(TagSet::tag, tagSet -> onlyValue(tagSet)));
    }

    private String onlyValue(final TagSet tagSet) {
        return ((TagValue.Str) tagSet.values().iterator().next()).value();
    }
}
