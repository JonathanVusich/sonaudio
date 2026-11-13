package dev.javax.sonaudio.format.alac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.Format;
import dev.javax.sonaudio.metadata.RawMetadata;
import dev.javax.sonaudio.metadata.Tag;
import dev.javax.sonaudio.metadata.TagSet;
import dev.javax.sonaudio.metadata.TagValue;
import dev.javax.sonaudio.samples.AlacSample;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AlacParserTest {

    private final AlacParser parser = new AlacParser();

    @Test
    void readsAlac() {
        assertThat(parser.format()).isEqualTo(Format.ALAC);
    }

    @Test
    void readsTheTagsOfATaggedFile() throws IOException, InvalidFormatException {
        try (final var channel = AlacSample.TAGGED.channel()) {
            final var metadata = parser.parse(channel).metadata();

            assertThat(metadata.findValues(Tag.TRACK_TITLE)).containsExactly(new TagValue.Str("Test Title"));
            assertThat(metadata.findValues(Tag.TRACK_ARTIST)).containsExactly(new TagValue.Str("Test Artist"));
            assertThat(metadata.findValues(Tag.ALBUM_ARTIST)).containsExactly(new TagValue.Str("Test Album Artist"));
            assertThat(metadata.findValues(Tag.RELEASE_TITLE)).containsExactly(new TagValue.Str("Test Album"));
            assertThat(metadata.findValues(Tag.COMPOSER)).containsExactly(new TagValue.Str("Test Composer"));
            assertThat(metadata.findValues(Tag.TRACK_NUMBER)).containsExactly(new TagValue.Int(3));
            assertThat(metadata.findValues(Tag.DISC_NUMBER)).containsExactly(new TagValue.Int(1));
        }
    }

    // The names Picard's freeform atoms get are those the same tags have in FLAC's Vorbis comments and MP3's ID3 tags.
    @Test
    void namesPicardsFreeformTagsAsFlacAndMp3DoAndKeepsTheRestInLowerCase() throws IOException, InvalidFormatException {
        try (final var channel = AlacSample.TAGGED.channel()) {
            final var unknown = unknownTags(parser.parse(channel).metadata());

            assertThat(unknown)
                    .containsEntry("musicbrainz_trackid", strings("0c9a6f2e-4f7d-4a3b-9f55-5d3a3c1b7e01"))
                    .containsEntry("musicbrainz_releasetrackid", strings("6a1d0e0b-2c4e-4d0f-8a32-7b5c8e9d1f02"))
                    .containsEntry("musicbrainz_artistid", strings("11111111-2222-4333-8444-555555555504"))
                    .containsEntry("musicbrainz_albumartistid", strings("66666666-7777-4888-9999-aaaaaaaaaa05"))
                    .containsKeys("musicbrainz_albumid", "musicbrainz_releasegroupid")
                    .containsEntry("releasetype", strings("album"))
                    .containsEntry("releasestatus", strings("official"))
                    .containsEntry("releasecountry", strings("XW"))
                    .containsEntry("artists", strings("Test Artist"))
                    .containsEntry("script", strings("Latn"))
                    .containsEntry("label", strings("Test Label"))
                    .containsEntry("catalognumber", strings("TEST-001"))
                    .containsEntry("barcode", strings("0000000000000"))
                    .containsEntry("originaldate", strings("2019-11-01"))
                    .containsEntry("date", strings("2020-05-06"));
        }
    }

    @Test
    void readsTheOriginalYearFromTheFreeformAtomPicardWritesIt() throws IOException, InvalidFormatException {
        final var file = AlacTestFiles.withFreeformItem(
                AlacTestFiles.bytes(AlacSample.STEREO16_LEVEL1), "originalyear", "2019-11-01");

        final var metadata = parser.parse(new SeekableInMemoryByteChannel(file)).metadata();

        assertThat(metadata.findValues(Tag.RELEASE_YEAR)).containsExactly(new TagValue.Int(2019));
        assertThat(unknownTags(metadata)).doesNotContainKey("originalyear");
    }

    @Test
    void refusesAnOriginalYearThatIsNotAYear() throws IOException {
        final var file = AlacTestFiles.withFreeformItem(
                AlacTestFiles.bytes(AlacSample.STEREO16_LEVEL1), "originalyear", "sometime");

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(file)))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("sometime");
    }

    @Test
    void readsAFileWithoutTags() throws IOException, InvalidFormatException {
        try (final var channel = AlacSample.MONO16.channel()) {
            final var metadata = parser.parse(channel).metadata();

            assertThat(metadata.unknownTags()).isEmpty();
            assertThat(metadata.findValues(Tag.TRACK_TITLE)).isEmpty();
        }
    }

    @ParameterizedTest
    @CsvSource({
        "STEREO16_LEVEL0, 22050, 44100",
        "STEREO24_LEVEL0, 24000, 96000",
        "MONO16, 17640, 44100",
        "SURROUND51_16, 8820, 44100",
        "MONO16_8KHZ, 6400, 8000",
        "MONO16_192KHZ, 38400, 192000",
        "ESCAPE_FRAMES, 6615, 44100",
        "TAGGED, 6615, 44100"
    })
    void givesTheDurationOfTheAudio(final AlacSample sample, final long samples, final int sampleRate)
            throws IOException, InvalidFormatException {
        try (final var channel = sample.channel()) {
            final var parsed = parser.parse(channel);

            assertThat(parsed.audio().streamInfo().sampleCount()).isEqualTo(samples);
            assertThat(parsed.metadata().duration()).isEqualTo(Duration.ofNanos(samples * 1_000_000_000L / sampleRate));
            assertThat(parsed.audio().sampleRate()).isEqualTo(sampleRate);
        }
    }

    @Test
    void readsTheCookie() throws IOException, InvalidFormatException {
        try (final var channel = AlacSample.STEREO16_LEVEL1.channel()) {
            final var cookie = parser.parse(channel).audio().streamInfo().cookie();

            assertThat(cookie.frameLength()).isEqualTo(4096);
            assertThat(cookie.bitsPerSample()).isEqualTo(16);
            assertThat(cookie.channelCount()).isEqualTo(2);
            assertThat(cookie.sampleRate()).isEqualTo(44100);
            assertThat(cookie.riceHistoryMultiplier()).isEqualTo(40);
            assertThat(cookie.riceInitialHistory()).isEqualTo(10);
            assertThat(cookie.riceLimit()).isEqualTo(14);
        }
    }

    // Both files hold the same audio, with the moov box before and after it.
    @Test
    void readsAMoovBoxBeforeAndAfterTheAudioTheSame() throws IOException, InvalidFormatException {
        try (final var before = AlacSample.MOOV_BEFORE_MDAT.channel();
                final var after = AlacSample.MOOV_AFTER_MDAT.channel()) {
            final var beforeAudio = parser.parse(before).audio();
            final var afterAudio = parser.parse(after).audio();

            assertThat(AlacPcm.decodeToBytes(afterAudio)).isEqualTo(AlacPcm.decodeToBytes(beforeAudio));
            assertThat(afterAudio.streamInfo()).isEqualTo(beforeAudio.streamInfo());
        }
    }

    @Test
    void readsAMoovBoxOfA64BitSize() throws IOException, InvalidFormatException {
        final var file = AlacTestFiles.withLargeMoovSize(AlacTestFiles.bytes(AlacSample.MOOV_AFTER_MDAT));

        final var audio = parser.parse(new SeekableInMemoryByteChannel(file)).audio();

        assertThat(audio.streamInfo().sampleCount()).isEqualTo(6615);
        assertThat(AlacPcm.decodeToBytes(audio)).hasSize(6615 * 2 * 2);
    }

    @Test
    void readsAMoovBoxThatGoesOnToTheEndOfTheFile() throws IOException, InvalidFormatException {
        final var file = AlacTestFiles.withMoovToTheEnd(AlacTestFiles.bytes(AlacSample.MOOV_AFTER_MDAT));

        final var audio = parser.parse(new SeekableInMemoryByteChannel(file)).audio();

        assertThat(audio.streamInfo().sampleCount()).isEqualTo(6615);
    }

    @Test
    void refusesAudioThatIsNotAlac() throws IOException {
        final var file = AlacTestFiles.renamed(AlacTestFiles.bytes(AlacSample.MOOV_AFTER_MDAT), "alac", "mp4a");

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(file)))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("isn't ALAC")
                .hasMessageContaining("mp4a");
    }

    @Test
    void refusesAFileWithoutAnAudioTrack() throws IOException {
        final var file = AlacTestFiles.renamed(AlacTestFiles.bytes(AlacSample.MOOV_AFTER_MDAT), "soun", "vide");

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(file)))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("no audio track");
    }

    @Test
    void refusesAFragmentedFileWhoseMovieExtendsItsTracks() throws IOException {
        final var file = AlacTestFiles.renamed(AlacTestFiles.bytes(AlacSample.MOOV_AFTER_MDAT), "udta", "mvex");

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(file)))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("fragmented");
    }

    @Test
    void refusesAFileWithMovieFragments() throws IOException {
        final var bytes = AlacTestFiles.bytes(AlacSample.MOOV_AFTER_MDAT);
        final var free = AlacTestFiles.indexOf(bytes, "free", 0);
        System.arraycopy("moof".getBytes(StandardCharsets.ISO_8859_1), 0, bytes, free, 4);

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(bytes)))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("fragmented");
    }

    @Test
    void refusesAFileWithoutAMoovBox() throws IOException {
        final var bytes = AlacTestFiles.bytes(AlacSample.MOOV_AFTER_MDAT);
        final var moov = AlacTestFiles.lastIndexOf(bytes, "moov", bytes.length);
        System.arraycopy("free".getBytes(StandardCharsets.ISO_8859_1), 0, bytes, moov, 4);

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(bytes)))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("no moov box");
    }

    @Test
    void refusesAFileThatIsNotMpeg4() {
        final var bytes = "This is not an MPEG-4 file, it is a text file.".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(bytes)))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void refusesAFileCutShortInItsMoovBox() throws IOException {
        final var bytes = AlacTestFiles.bytes(AlacSample.MOOV_AFTER_MDAT);
        final var cut = Arrays.copyOf(bytes, bytes.length - 50);

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(cut)))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("past the end of the file");
    }

    @Test
    void refusesABoxSmallerThanItsHeader() throws IOException {
        final var bytes = AlacTestFiles.bytes(AlacSample.MOOV_AFTER_MDAT);
        // The ftyp box's size, as 4.
        bytes[3] = 4;

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(bytes)))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("smaller than its header");
    }

    @Test
    void refusesACookieOfAnUnsupportedBitDepth() throws IOException {
        final var bytes = AlacTestFiles.bytes(AlacSample.MOOV_AFTER_MDAT);
        final var cookie = AlacTestFiles.lastIndexOf(bytes, "alac", bytes.length);
        // After the box's type come its version, then the frame length and the compatible version.
        bytes[cookie + 4 + 4 + 4 + 1] = 12;

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(bytes)))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("12 bits");
    }

    @Test
    void refusesACookieOfNoChannels() throws IOException {
        final var bytes = AlacTestFiles.bytes(AlacSample.MOOV_AFTER_MDAT);
        final var cookie = AlacTestFiles.lastIndexOf(bytes, "alac", bytes.length);
        // Then come the compatible version, the bit depth, the three Rice parameters and the channel count.
        bytes[cookie + 4 + 4 + 4 + 1 + 1 + 3] = 0;

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(bytes)))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("0 channels");
    }

    @Test
    void refusesAFreeformItemWithoutData() throws IOException {
        final var bytes = AlacTestFiles.bytes(AlacSample.STEREO16_LEVEL1);
        final var file = AlacTestFiles.withFreeformItem(bytes, "label", "value");
        final var data = AlacTestFiles.lastIndexOf(file, "data", file.length);
        System.arraycopy("free".getBytes(StandardCharsets.ISO_8859_1), 0, file, data, 4);

        assertThatThrownBy(() -> parser.parse(new SeekableInMemoryByteChannel(file)))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("freeform");
    }

    private static Map<String, Set<TagValue>> unknownTags(final RawMetadata metadata) {
        return metadata.unknownTags().collect(Collectors.toMap(TagSet::tag, TagSet::values));
    }

    private static Set<TagValue> strings(final String... values) {
        return Arrays.stream(values).<TagValue>map(TagValue.Str::new).collect(Collectors.toSet());
    }
}
