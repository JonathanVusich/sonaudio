package dev.javax.sonaudio.format.flac;

import static java.util.stream.Collectors.groupingBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.MD5Checksum;
import dev.javax.sonaudio.metadata.RawMetadata;
import dev.javax.sonaudio.metadata.Tag;
import dev.javax.sonaudio.metadata.TagSet;
import dev.javax.sonaudio.metadata.TagValue;
import dev.javax.sonaudio.samples.FlacSample;

class FlacParserTest {

    @ParameterizedTest
    @EnumSource(FlacSample.class)
    void parseAllMetadataReadsEveryBlock(FlacSample sample) throws IOException, InvalidFormatException {
        try (final var inputStream = sample.inputStream()) {
            final var result = new FlacParser().parseAllMetadata(inputStream);

            assertThat(result.metadataBlocks()).containsKeys(BlockType.STREAM_INFO, BlockType.VORBIS_COMMENT);
        }
    }

    @Test
    void parseColdplay() throws IOException, InvalidFormatException {
        final var metadata = parseFlac(FlacSample.COLDPLAY);

        assertThat(metadata.duration().toSeconds()).isEqualTo(238);

        assertThat(metadata.findValues(Tag.TRACK_TITLE)).containsExactly(new TagValue.Str("A Whisper"));
        assertThat(metadata.findValues(Tag.TRACK_ARTIST)).containsExactly(new TagValue.Str("Coldplay"));
        assertThat(metadata.findValues(Tag.RELEASE_TITLE))
                .containsExactly(new TagValue.Str("A Rush of Blood to the Head"));
        assertThat(metadata.findValues(Tag.TRACK_NUMBER)).containsExactly(new TagValue.Int(9));
    }

    @Test
    void parsePicardTaggedFile() throws IOException, InvalidFormatException {
        final var metadata = parseFlac(FlacSample.LUCAS_FLOYD);

        assertThat(metadata.duration()).isEqualTo(Duration.ofSeconds(57, 160_000_000));

        assertThat(metadata.findValues(Tag.TRACK_NUMBER)).containsExactly(new TagValue.Int(8));
        assertThat(metadata.findValues(Tag.DISC_NUMBER)).containsExactly(new TagValue.Int(2));
        assertThat(metadata.findValues(Tag.ALBUM_ARTIST))
                .containsExactly(new TagValue.Str("Philip Glass; Paul Barnes"));
        assertThat(metadata.findValues(Tag.RELEASE_YEAR)).containsExactly(new TagValue.Int(2016));
        assertThat(metadata.unknownTags())
                .contains(new TagSet(
                        "musicbrainz_trackid", Set.of(new TagValue.Str("d19c6734-0690-4ee6-9228-91b95f32554d"))));
    }

    @Test
    void parseEmbeddedPicture() throws IOException, InvalidFormatException {
        try (final var inputStream = FlacSample.LUCAS_FLOYD.inputStream()) {
            final var metadata = new FlacParser().parseAllMetadata(inputStream);

            final var block = metadata.metadataBlocks().get(BlockType.PICTURE).getFirst();
            assertThat(block).isInstanceOfSatisfying(Picture.class, picture -> {
                assertThat(picture.pictureType()).isEqualTo(PictureType.FRONT_COVER);
                assertThat(picture.mediaType()).isEqualTo("image/jpeg");
                assertThat(picture.description()).isEmpty();
                assertThat(picture.width()).isEqualTo(360);
                assertThat(picture.height()).isEqualTo(327);
                assertThat(picture.data()).hasSize(62_895).startsWith(0xFF, 0xD8);
            });
        }
    }

    @Test
    void parseFileWithOnlyUnknownTags() throws IOException, InvalidFormatException {
        final var parsed = parseFlac(FlacSample.SAMPLE_3);

        assertThat(parsed.findValues(Tag.TRACK_TITLE)).isEmpty();
        assertThat(parsed.unknownTags())
                .containsExactly(new TagSet("encoder", Set.of(new TagValue.Str("Lavf58.29.100"))));
    }

    @Test
    void parseFileWithoutVorbisComment() throws IOException, InvalidFormatException {
        final var parsed = parseFlac(flacStream(streamInfo(lastHeader(BlockType.STREAM_INFO))));

        assertThat(parsed.duration()).isEqualTo(Duration.ofSeconds(10));
        assertThat(parsed.unknownTags()).isEmpty();
    }

    @Test
    void fieldNamesAreCaseInsensitive() throws IOException, InvalidFormatException {
        final var parsed = parseFlac(flacStream(
                streamInfo(blockHeader(BlockType.STREAM_INFO)),
                vorbisComment(lastHeader(BlockType.VORBIS_COMMENT), new Field("TiTlE", "Mixed Case"))));

        assertThat(parsed.findValues(Tag.TRACK_TITLE)).containsExactly(new TagValue.Str("Mixed Case"));
    }

    @Test
    void rejectNonFlacInput() {
        final var notFlac = new ByteArrayInputStream("ID3\u0004 not a flac file".getBytes());

        assertThatThrownBy(() -> parseFlac(notFlac)).isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void rejectMissingStreamInfo() {
        final var comment = vorbisComment(lastHeader(BlockType.VORBIS_COMMENT), new Field("TITLE", "No Stream Info"));
        assertThatThrownBy(() -> parseFlac(flacStream(comment)))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("STREAMINFO");
    }

    @Test
    void rejectVorbisCommentFieldWithoutEquals() {
        final var stream = commentStream();
        stream[indexOfTitle(stream) + "TITLE".length()] = 'x';

        assertThatThrownBy(() -> parseFlac(new ByteArrayInputStream(stream)))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void rejectVorbisCommentWithMoreFieldsThanFitInItsBlock() {
        final var stream = commentStream();
        final var countAt = indexOfTitle(stream) - 8;
        Arrays.fill(stream, countAt, countAt + 3, (byte) 0xFF);
        stream[countAt + 3] = 0x7F;

        assertThatThrownBy(() -> parseFlac(new ByteArrayInputStream(stream)))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void rejectApplicationBlockTooSmallForItsId() {
        final var stream = streamEndingIn(0x82, (byte) 1, (byte) 2);

        assertThatThrownBy(() -> parseFlac(stream)).isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void rejectPictureWithNegativeMediaTypeLength() {
        // Picture type 3, then a media type length of -1.
        final var stream = streamEndingIn(
                0x86, (byte) 0, (byte) 0, (byte) 0, (byte) 3, (byte) -1, (byte) -1, (byte) -1, (byte) -1);

        assertThatThrownBy(() -> parseFlac(stream)).isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void rejectVorbisCommentFieldOfNegativeLength() {
        final var stream = commentStream();
        final var lengthAt = indexOfTitle(stream) - 4;
        Arrays.fill(stream, lengthAt, lengthAt + 4, (byte) 0xFF);

        assertThatThrownBy(() -> parseFlac(new ByteArrayInputStream(stream)))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void writtenBlocksParseBackUnchanged() throws IOException, InvalidFormatException {
        final var blocks = new Block[] {
            streamInfo(blockHeader(BlockType.STREAM_INFO)),
            new SeekTable(blockHeader(BlockType.SEEK_TABLE), List.of(new SeekPoint(0, 0, (short) 4096))),
            vorbisComment(blockHeader(BlockType.VORBIS_COMMENT), new Field("TITLE", "Round Trip")),
            new Application(blockHeader(BlockType.APPLICATION), 0x52494646, new byte[] {1, 2, 3, 4}),
            cueSheet(blockHeader(BlockType.CUESHEET)),
            new Picture(
                    blockHeader(BlockType.PICTURE),
                    PictureType.FRONT_COVER,
                    "image/png",
                    "Front ✓",
                    2,
                    1,
                    24,
                    0,
                    new byte[] {9, 8, 7}),
            new Padding(new BlockHeader(BlockPosition.LAST, BlockType.PADDING, 1024))
        };

        final var read = new FlacParser().parseAllMetadata(flacStream(blocks));

        // The writer works out each block's size, so only padding's size is known up front.
        assertThat(read)
                .usingRecursiveComparison()
                .ignoringFieldsMatchingRegexes(".*header\\.size")
                .isEqualTo(new FlacMetadata(Arrays.stream(blocks)
                        .collect(groupingBy(block -> block.header().blockType()))));
        assertThat(read.metadataBlocks()
                        .get(BlockType.PADDING)
                        .getFirst()
                        .header()
                        .size())
                .isEqualTo(1024);
        // Sizes from the RFC 9639 layouts: a cuesheet is 396 bytes plus 36 per track and 12 per index point, and a
        // picture is 32 bytes plus its media type, description and data.
        assertThat(read.metadataBlocks()
                        .get(BlockType.CUESHEET)
                        .getFirst()
                        .header()
                        .size())
                .isEqualTo(396 + 3 * 36 + 3 * 12);
        assertThat(read.metadataBlocks()
                        .get(BlockType.PICTURE)
                        .getFirst()
                        .header()
                        .size())
                .isEqualTo(32 + 9 + 9 + 3);
    }

    private static CueSheet cueSheet(final BlockHeader header) {
        final var firstTrack = new CueSheetTrack(
                0, 1, "USSM19900000", TrackType.AUDIO, PreEmphasis.NONE, List.of(new CueSheetIndexPoint(0, 1)));
        final var dataTrack = new CueSheetTrack(
                588 * 100,
                2,
                "",
                TrackType.NON_AUDIO,
                PreEmphasis.APPLIED,
                List.of(new CueSheetIndexPoint(0, 0), new CueSheetIndexPoint(588 * 2, 1)));
        // CD-DA cuesheets end with a lead-out track (number 170) that has no index points.
        final var leadOut = new CueSheetTrack(588 * 300, 170, "", TrackType.AUDIO, PreEmphasis.NONE, List.of());
        return new CueSheet(
                header, "1234567890123", 88_200, CueSheetMedium.CD_DA, List.of(firstTrack, dataTrack, leadOut));
    }

    private static StreamInfo streamInfo(final BlockHeader header) {
        // 10 seconds of 16-bit stereo at 44.1 kHz.
        return new StreamInfo(header, new MD5Checksum(new byte[16]), 4096, 4096, 0, 0, 44_100, 2, 16, 441_000);
    }

    private static VorbisComment vorbisComment(final BlockHeader header, final Field... fields) {
        return new VorbisComment(header, "sona-test", List.of(fields));
    }

    // FlacWriter works out the size of every block but padding, so it's left at 0 here.
    private static BlockHeader blockHeader(final BlockType blockType) {
        return new BlockHeader(BlockPosition.NOT_LAST, blockType, 0);
    }

    private static BlockHeader lastHeader(final BlockType blockType) {
        return new BlockHeader(BlockPosition.LAST, blockType, 0);
    }

    private static InputStream flacStream(final Block... blocks) throws IOException {
        final var outputStream = new ByteArrayOutputStream();
        final var dataStream = new DataOutputStream(outputStream);
        final var flacWriter = new FlacWriter();
        flacWriter.writeFlacHeader(dataStream);
        for (final var block : blocks) {
            flacWriter.writeBlock(dataStream, block);
        }
        return new ByteArrayInputStream(outputStream.toByteArray());
    }

    private static byte[] commentStream() {
        final var comment = vorbisComment(lastHeader(BlockType.VORBIS_COMMENT), new Field("TITLE", "x"));
        try {
            return flacStream(streamInfo(blockHeader(BlockType.STREAM_INFO)), comment)
                    .readAllBytes();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // A STREAMINFO block followed by a last block of the given type whose contents are written by hand.
    private static InputStream streamEndingIn(final int lastBlockHeader, final byte... contents) {
        final var outputStream = new ByteArrayOutputStream();
        try {
            flacStream(streamInfo(blockHeader(BlockType.STREAM_INFO))).transferTo(outputStream);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        outputStream.write(lastBlockHeader);
        outputStream.write(0);
        outputStream.write(contents.length >> 8);
        outputStream.write(contents.length);
        outputStream.writeBytes(contents);
        // Audio follows, so a block that reads past its declared size finds bytes there rather than the end.
        outputStream.writeBytes(new byte[8]);
        return new ByteArrayInputStream(outputStream.toByteArray());
    }

    private static int indexOfTitle(final byte[] stream) {
        final var title = "TITLE=x".getBytes(StandardCharsets.US_ASCII);
        for (var index = 0; index <= stream.length - title.length; index++) {
            if (Arrays.equals(stream, index, index + title.length, title, 0, title.length)) {
                return index;
            }
        }
        throw new IllegalStateException("No TITLE field");
    }

    private static RawMetadata parseFlac(final FlacSample sample) throws IOException, InvalidFormatException {
        try (final var inputStream = sample.inputStream()) {
            return parseFlac(inputStream);
        }
    }

    private static RawMetadata parseFlac(final InputStream inputStream) throws IOException, InvalidFormatException {
        return new FlacParser()
                .parse(new SeekableInMemoryByteChannel(inputStream.readAllBytes()))
                .metadata();
    }
}
