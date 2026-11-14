package dev.javax.sonaudio.internal;

import dev.javax.sonaudio.metadata.Tag;
import dev.javax.sonaudio.metadata.TagValue;
import java.time.Year;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The Vorbis comment fields sonaudio maps to a {@link Tag}, each by its name in lower case.
 */
@RequiredArgsConstructor
@Getter
public enum VorbisTag {
    ARTIST("artist", Tag.TRACK_ARTIST),
    ALBUM_ARTIST("albumartist", Tag.ALBUM_ARTIST),
    TITLE("title", Tag.TRACK_TITLE),
    ALBUM("album", Tag.RELEASE_TITLE),
    TRACK_NUMBER("tracknumber", Tag.TRACK_NUMBER),
    DISC_NUMBER("discnumber", Tag.DISC_NUMBER),
    ORIGINAL_YEAR("originalyear", Tag.RELEASE_YEAR),
    COMPOSER("composer", Tag.COMPOSER);

    private static final Map<String, VorbisTag> TAGS = Arrays.stream(VorbisTag.values())
            .collect(Collectors.toUnmodifiableMap(VorbisTag::getFieldName, Function.identity()));

    private final String fieldName;
    private final Tag tag;

    /**
     * @param fieldValue the text of a field of this tag
     * @return the value the text means
     */
    public TagValue toTagValue(final String fieldValue) {
        return switch (this) {
            case ALBUM_ARTIST, TITLE, ALBUM, ARTIST, COMPOSER -> new TagValue.Str(fieldValue);
            case TRACK_NUMBER, DISC_NUMBER -> new TagValue.Int(Integer.parseInt(fieldValue));
            case ORIGINAL_YEAR -> new TagValue.Int(Year.parse(fieldValue).getValue());
        };
    }

    /**
     * @param fieldName a Vorbis comment field's name, in lower case
     * @return the tag the name maps to, or the name when sonaudio has no tag for it
     */
    public static VorbisFieldTag fieldTag(final String fieldName) {
        final var vorbisTag = TAGS.get(fieldName);
        if (vorbisTag == null) {
            return new VorbisFieldTag.Unknown(fieldName);
        }
        return new VorbisFieldTag.Known(vorbisTag);
    }
}
