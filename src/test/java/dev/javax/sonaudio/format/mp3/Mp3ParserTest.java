package dev.javax.sonaudio.format.mp3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;
import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.mp3.Id3Frame.TextFrame;
import dev.javax.sonaudio.format.mp3.Id3Frame.UserTextFrame;
import dev.javax.sonaudio.metadata.RawMetadata;
import dev.javax.sonaudio.metadata.Tag;
import dev.javax.sonaudio.metadata.TagSet;
import dev.javax.sonaudio.metadata.TagValue;
import dev.javax.sonaudio.samples.FlacSample;
import dev.javax.sonaudio.samples.Mp3Sample;

class Mp3ParserTest {

    @Test
    void readsId3v24Tag() throws IOException, InvalidFormatException {
        final var metadata = parseMp3(Mp3Sample.STEREO_CBR_ID3V24).metadata();

        assertThat(metadata.findValues(Tag.TRACK_TITLE)).containsExactly(new TagValue.Str("A Whisper"));
        assertThat(metadata.findValues(Tag.TRACK_ARTIST)).containsExactly(new TagValue.Str("Coldplay"));
        assertThat(metadata.findValues(Tag.ALBUM_ARTIST)).containsExactly(new TagValue.Str("Coldplay"));
        assertThat(metadata.findValues(Tag.RELEASE_TITLE))
                .containsExactly(new TagValue.Str("A Rush of Blood to the Head"));
        assertThat(metadata.findValues(Tag.TRACK_NUMBER)).containsExactly(new TagValue.Int(9));
        assertThat(metadata.findValues(Tag.DISC_NUMBER)).containsExactly(new TagValue.Int(1));
        assertThat(metadata.findValues(Tag.RELEASE_YEAR)).containsExactly(new TagValue.Int(2002));
        assertThat(metadata.findValues(Tag.COMPOSER)).containsExactly(new TagValue.Str("Guy Berryman"));
    }

    // Picard's TXXX descriptions are read under the names it gives the same tags in FLAC files.
    @Test
    void namesUnknownTagsAsVorbisCommentsDo() throws IOException, InvalidFormatException {
        final var metadata = parseMp3(Mp3Sample.STEREO_CBR_ID3V24).metadata();

        assertThat(unknownTag(metadata, "musicbrainz_albumid"))
                .containsExactly(new TagValue.Str("5b59d66b-8902-4350-b2e3-324037ef4cb0"));
        // Frames that hold none of Sona's tags are kept under their frame IDs.
        assertThat(unknownTag(metadata, "tsse")).containsExactly(new TagValue.Str("Lavf58.29.100"));
    }

    // The LAME tag gives the encoder's delay and padding, so the duration is exactly the one second encoded.
    @Test
    void takesLengthFromStream() throws IOException, InvalidFormatException {
        final var parsed = parseMp3(Mp3Sample.STEREO_CBR_ID3V24);

        assertThat(parsed.metadata().duration()).isEqualTo(Duration.ofSeconds(1));
        assertThat(parsed.audio().sampleRate()).isEqualTo(44100);
        assertThat(parsed.audio().bitsPerSample()).isEqualTo(16);
    }

    @Test
    void readsId3v23Tag() throws IOException, InvalidFormatException {
        final var metadata = parseMp3(Mp3Sample.MONO_VBR_ID3V23).metadata();

        assertThat(metadata.findValues(Tag.TRACK_ARTIST)).containsExactly(new TagValue.Str("Lucas Floyd"));
        assertThat(metadata.findValues(Tag.TRACK_NUMBER)).containsExactly(new TagValue.Int(3));
        assertThat(metadata.findValues(Tag.RELEASE_YEAR)).containsExactly(new TagValue.Int(2019));
    }

    // The file has both tags with the same title, and only ID3v2's is read.
    @Test
    void prefersId3v2ToId3v1() throws IOException, InvalidFormatException {
        final var metadata = parseMp3(Mp3Sample.MONO_VBR_ID3V23).metadata();

        assertThat(metadata.findValues(Tag.TRACK_TITLE)).containsExactly(new TagValue.Str("Canyon"));
        assertThat(metadata.findValues(Tag.RELEASE_TITLE)).containsExactly(new TagValue.Str("Canyon"));
    }

    @Test
    void readsId3v1Tag() throws IOException, InvalidFormatException {
        final var metadata = parseMp3(Mp3Sample.NO_XING_ID3V1).metadata();

        assertThat(metadata.findValues(Tag.TRACK_TITLE)).containsExactly(new TagValue.Str("Untagged"));
        assertThat(metadata.findValues(Tag.TRACK_ARTIST)).containsExactly(new TagValue.Str("Nobody"));
        assertThat(metadata.findValues(Tag.RELEASE_TITLE)).containsExactly(new TagValue.Str("Nothing"));
        assertThat(metadata.findValues(Tag.TRACK_NUMBER)).containsExactly(new TagValue.Int(7));
    }

    // ID3v2.2's three-letter frames read as the ID3v2.3 frames that replaced them.
    @Test
    void readsId3v22Tag() throws IOException, InvalidFormatException {
        final var metadata = parseMp3(Mp3Sample.ID3V22).metadata();

        assertThat(metadata.findValues(Tag.TRACK_TITLE)).containsExactly(new TagValue.Str("Politik"));
        assertThat(metadata.findValues(Tag.TRACK_ARTIST)).containsExactly(new TagValue.Str("Coldplay"));
        assertThat(metadata.findValues(Tag.RELEASE_TITLE))
                .containsExactly(new TagValue.Str("A Rush of Blood to the Head"));
        assertThat(metadata.findValues(Tag.TRACK_NUMBER)).containsExactly(new TagValue.Int(1));
        assertThat(metadata.findValues(Tag.RELEASE_YEAR)).containsExactly(new TagValue.Int(2002));
        assertThat(unknownTag(metadata, "musicbrainz_albumid"))
                .containsExactly(new TagValue.Str("5b59d66b-8902-4350-b2e3-324037ef4cb0"));
        assertThat(unknownTag(metadata, "musicbrainz_trackid"))
                .containsExactly(new TagValue.Str("0c4a1b1a-0a6b-4c5b-9d2a-1d2e3f4a5b6c"));
    }

    @Test
    void inflatesCompressedId3v23Frame() throws IOException, InvalidFormatException {
        final var metadata = parseMp3(Mp3Sample.COMPRESSED_ID3V23).metadata();

        assertThat(metadata.findValues(Tag.TRACK_TITLE)).containsExactly(new TagValue.Str("Compressed in ID3v2.3"));
        assertThat(metadata.findValues(Tag.TRACK_ARTIST)).containsExactly(new TagValue.Str("Coldplay"));
    }

    // The artist is ÿàn: an 0xFF byte before one of 0xE0, which unsynchronisation separates with a zero.
    @Test
    void readsCompressedAndUnsynchronisedId3v24Frames() throws IOException, InvalidFormatException {
        final var metadata = parseMp3(Mp3Sample.COMPRESSED_ID3V24).metadata();

        assertThat(metadata.findValues(Tag.TRACK_TITLE)).containsExactly(new TagValue.Str("Compressed in ID3v2.4"));
        assertThat(metadata.findValues(Tag.TRACK_ARTIST)).containsExactly(new TagValue.Str("ÿàn"));
    }

    // An appended tag updates the one before the audio, and both take precedence over ID3v1.
    @Test
    void readsAppendedTagBeforeOthers() throws IOException, InvalidFormatException {
        final var parsed = parseMp3(Mp3Sample.APPENDED_ID3V24);
        final var metadata = parsed.metadata();

        assertThat(metadata.findValues(Tag.TRACK_TITLE)).containsExactly(new TagValue.Str("Appended title"));
        assertThat(metadata.findValues(Tag.TRACK_ARTIST)).containsExactly(new TagValue.Str("Prepended artist"));
        assertThat(metadata.findValues(Tag.RELEASE_TITLE)).containsExactly(new TagValue.Str("v1 album"));
        // Five frames sit between the tags.
        assertThat(parsed.audio().streamInfo().frameCount()).isEqualTo(5);
    }

    // FFmpeg reads the tags a file starts with one after another, each updating the last.
    @Test
    void readsEveryTagBeforeAudio() throws IOException, InvalidFormatException {
        final var parsed = parseMp3(Mp3Sample.TWO_ID3V2_TAGS);
        final var metadata = parsed.metadata();

        assertThat(metadata.findValues(Tag.TRACK_TITLE)).containsExactly(new TagValue.Str("Politik"));
        assertThat(metadata.findValues(Tag.COMPOSER)).containsExactly(new TagValue.Str("Guy Berryman"));
        assertThat(parsed.audio().streamInfo().frameCount()).isEqualTo(5);
    }

    // Without a VBR header the frames are counted up to the ID3v1 tag.
    @Test
    void countsFramesUpToId3v1Tag() throws IOException, InvalidFormatException {
        assertThat(parseMp3(Mp3Sample.NO_XING_ID3V1).audio().streamInfo().frameCount())
                .isEqualTo(40);
    }

    // The VBR header's frame is silence, not audio, so the decoder starts after it.
    @Test
    void leavesChannelAtFirstFrameOfAudio() throws IOException, InvalidFormatException {
        try (final var channel = Mp3Sample.STEREO_CBR_ID3V24.channel()) {
            final var streamInfo = new Mp3Parser().parse(channel).audio().streamInfo();

            assertThat(channel.position()).isEqualTo(streamInfo.audioStart());
            assertThat(nextHeader(channel)).isEqualTo(HexFormat.of().parseHex("FFFB9064"));
        }
    }

    @Test
    void rejectsFileWithoutFrames() throws IOException {
        try (final var channel = FlacSample.COLDPLAY.channel()) {
            assertThatThrownBy(() -> new Mp3Parser().parse(channel))
                    .isInstanceOf(InvalidFormatException.class)
                    .hasMessage("MP3 has no frames of audio!");
        }
    }

    @Test
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> new Mp3Parser().parse(new SeekableInMemoryByteChannel(new byte[0])))
                .isInstanceOf(InvalidFormatException.class);
    }

    // Cutting off the ID3v1 tag and some of the last frame leaves that frame short, which FFmpeg still decodes.
    @Test
    void countsFrameCutShort() throws IOException, InvalidFormatException {
        final var bytes = sampleBytes(Mp3Sample.NO_XING_ID3V1);
        final var cut = Arrays.copyOf(bytes, bytes.length - 200);

        final var parsed = new Mp3Parser().parse(new SeekableInMemoryByteChannel(cut));

        assertThat(parsed.audio().streamInfo().frameCount()).isEqualTo(40);
    }

    @Test
    void readsId3v24TagFrames() throws IOException, InvalidFormatException {
        final var tag = readId3v2Tag(sampleBytes(Mp3Sample.STEREO_CBR_ID3V24));

        // The tag's padding runs up to the first frame, at 291.
        assertThat(tag.size()).isEqualTo(291);
        assertThat(tag.frames())
                .contains(
                        new TextFrame("TIT2", List.of("A Whisper")),
                        new TextFrame("TPE1", List.of("Coldplay")),
                        new TextFrame("TRCK", List.of("9/11")),
                        new TextFrame("TDOR", List.of("2002-08-12")),
                        new UserTextFrame("MusicBrainz Album Id", List.of("5b59d66b-8902-4350-b2e3-324037ef4cb0")));
    }

    @Test
    void readsId3v23TagFrames() throws IOException, InvalidFormatException {
        final var tag = readId3v2Tag(sampleBytes(Mp3Sample.MONO_VBR_ID3V23));

        assertThat(tag.size()).isEqualTo(133);
        assertThat(tag.frames())
                .contains(
                        new TextFrame("TIT2", List.of("Canyon")),
                        new TextFrame("TPE1", List.of("Lucas Floyd")),
                        new TextFrame("TRCK", List.of("3")),
                        new TextFrame("TORY", List.of("2019")));
    }

    @Test
    void readsEmptyTagWhenFileStartsWithAudio() throws IOException, InvalidFormatException {
        final var tag = readId3v2Tag(sampleBytes(Mp3Sample.NO_XING_ID3V1));

        assertThat(tag.size()).isZero();
        assertThat(tag.frames()).isEmpty();
    }

    // The tag's first frame, TIT2, starts after the 10-byte tag header, and its size follows its ID.
    @Test
    void rejectsFrameThatRunsPastTag() throws IOException {
        final var bytes = sampleBytes(Mp3Sample.STEREO_CBR_ID3V24);
        Arrays.fill(bytes, 14, 18, (byte) 0x7F);

        assertThatThrownBy(() -> readId3v2Tag(bytes))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessage("ID3v2 tag is cut short!");
    }

    // The padding starts after the last frame, so a byte at the end of the tag is in it.
    @Test
    void rejectsPaddingThatIsNotAllNuls() throws IOException {
        final var bytes = sampleBytes(Mp3Sample.STEREO_CBR_ID3V24);
        bytes[290] = 1;

        assertThatThrownBy(() -> readId3v2Tag(bytes))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessage("ID3v2 tag's padding isn't all NULs!");
    }

    private static Id3v2Tag readId3v2Tag(final byte[] bytes) throws IOException, InvalidFormatException {
        return new Mp3Parser().readId3v2Tag(new ByteArrayInputStream(bytes));
    }

    private static byte[] sampleBytes(final Mp3Sample sample) throws IOException {
        try (final var inputStream = sample.inputStream()) {
            return inputStream.readAllBytes();
        }
    }

    private static ParsedMp3 parseMp3(final Mp3Sample sample) throws IOException, InvalidFormatException {
        try (final var channel = sample.channel()) {
            return new Mp3Parser().parse(channel);
        }
    }

    private static Set<TagValue> unknownTag(final RawMetadata metadata, final String name) {
        return metadata.unknownTags()
                .filter(tagSet -> tagSet.tag().equals(name))
                .map(TagSet::values)
                .findFirst()
                .orElse(Set.of());
    }

    private static byte[] nextHeader(final SeekableByteChannel channel) throws IOException {
        return IOUtils.readFully(Channels.newInputStream(channel), FrameHeader.SIZE);
    }
}
