package dev.javax.sonaudio.format.flac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.junit.jupiter.api.Test;
import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.metadata.Blob;
import dev.javax.sonaudio.metadata.RawMetadata;
import dev.javax.sonaudio.metadata.RawMetadataBuilder;
import dev.javax.sonaudio.metadata.Tag;
import dev.javax.sonaudio.metadata.TagValue;

class FlacWriterTest {

    @Test
    void writtenMetadataParsesBackUnchanged() throws IOException, InvalidFormatException {
        final var builder = new RawMetadataBuilder(Duration.ofSeconds(57, 160_000_000));
        builder.addTag(Tag.TRACK_TITLE, new TagValue.Str("Canyon"));
        builder.addTag(Tag.ALBUM_ARTIST, new TagValue.Str("Philip Glass"));
        builder.addTag(Tag.ALBUM_ARTIST, new TagValue.Str("Paul Barnes"));
        builder.addTag(Tag.TRACK_NUMBER, new TagValue.Int(8));
        builder.addTag(Tag.RELEASE_YEAR, new TagValue.Int(2002));
        builder.addUnknownTag("musicbrainz_trackid", "1b3c1a6e-5d2b-4d0e-9a52-6ad0a6a0e1f2");

        final var metadata = builder.build();
        final var parsed = new FlacParser()
                .parse(new SeekableInMemoryByteChannel(writeFlac(metadata)))
                .metadata();

        assertThat(parsed.duration()).isEqualTo(metadata.duration());
        for (final var tag : Tag.values()) {
            assertThat(parsed.findValues(tag)).as(tag.name()).containsExactlyElementsOf(metadata.findValues(tag));
        }
        assertThat(parsed.unknownTags())
                .containsExactlyInAnyOrderElementsOf(metadata.unknownTags().toList());
    }

    @Test
    void rejectBinaryTags() {
        final var builder = new RawMetadataBuilder(Duration.ofSeconds(1));
        builder.addTag(Tag.TRACK_TITLE, new TagValue.Binary(Blob.copyOf(new byte[] {1})));
        final var metadata = builder.build();

        assertThatThrownBy(() -> writeFlac(metadata)).isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] writeFlac(final RawMetadata metadata) throws IOException {
        final var outputStream = new ByteArrayOutputStream();
        new FlacWriter().writeMetadata(metadata, outputStream);
        return outputStream.toByteArray();
    }
}
