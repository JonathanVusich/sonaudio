package dev.javax.sonaudio.format.mp3;

import java.time.Year;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import dev.javax.sonaudio.metadata.Tag;
import dev.javax.sonaudio.metadata.TagValue;

/**
 * The ID3v2.3 and ID3v2.4 text frames that hold Sona's tags, as Picard writes them.
 */
@RequiredArgsConstructor
@Getter
public enum Id3Tag {
    ARTIST("TPE1", Tag.TRACK_ARTIST),
    ALBUM_ARTIST("TPE2", Tag.ALBUM_ARTIST),
    TITLE("TIT2", Tag.TRACK_TITLE),
    ALBUM("TALB", Tag.RELEASE_TITLE),
    TRACK_NUMBER("TRCK", Tag.TRACK_NUMBER),
    DISC_NUMBER("TPOS", Tag.DISC_NUMBER),
    // ID3v2.3 has only a year for the original release, where ID3v2.4 has a timestamp.
    ORIGINAL_YEAR("TORY", Tag.RELEASE_YEAR),
    ORIGINAL_DATE("TDOR", Tag.RELEASE_YEAR),
    COMPOSER("TCOM", Tag.COMPOSER);

    private static final Map<String, Id3Tag> TAGS = Arrays.stream(Id3Tag.values())
            .collect(Collectors.toUnmodifiableMap(Id3Tag::getFrameId, Function.identity()));

    private static final int YEAR_LENGTH = 4;

    private final String frameId;
    private final Tag tag;

    public TagValue toTagValue(final String frameValue) {
        return switch (this) {
            case ARTIST, ALBUM_ARTIST, TITLE, ALBUM, COMPOSER -> new TagValue.Str(frameValue);
            // A number may be followed by the count, as in "9/11".
            case TRACK_NUMBER, DISC_NUMBER ->
                new TagValue.Int(Integer.parseInt(frameValue.split("/", 2)[0]));
            case ORIGINAL_YEAR -> new TagValue.Int(Year.parse(frameValue).getValue());
            // A timestamp starts with the year: yyyy, yyyy-MM, yyyy-MM-dd and so on.
            case ORIGINAL_DATE ->
                new TagValue.Int(Year.parse(frameValue.substring(0, Math.min(YEAR_LENGTH, frameValue.length())))
                        .getValue());
        };
    }

    public static Id3Tag fromFrameId(final String frameId) {
        return TAGS.get(frameId);
    }
}
