package dev.javax.sonaudio.internal;

import java.util.Locale;
import java.util.Map;
import lombok.experimental.UtilityClass;

/**
 * The names MusicBrainz Picard gives its tags in the formats that name them freely (ID3's {@code TXXX} frames, MP4's
 * freeform atoms), as the same tags are named in Vorbis comments, so a tag has one name whatever file it was read from.
 */
@UtilityClass
public final class PicardNames {

    private static final Map<String, String> NAMES = Map.of(
            "MusicBrainz Artist Id", "musicbrainz_artistid",
            "MusicBrainz Album Id", "musicbrainz_albumid",
            "MusicBrainz Album Artist Id", "musicbrainz_albumartistid",
            "MusicBrainz Release Group Id", "musicbrainz_releasegroupid",
            "MusicBrainz Release Track Id", "musicbrainz_releasetrackid",
            "MusicBrainz Work Id", "musicbrainz_workid",
            "MusicBrainz Disc Id", "musicbrainz_discid",
            "MusicBrainz Album Type", "releasetype",
            "MusicBrainz Album Status", "releasestatus",
            "MusicBrainz Album Release Country", "releasecountry");

    /**
     * @param description a user-defined tag's description, or a freeform tag's name, as it is written in the file
     * @return the name a Vorbis comment gives the tag if Picard writes it, otherwise the description in lower case
     */
    public static String userTextName(final String description) {
        return NAMES.getOrDefault(description, description.toLowerCase(Locale.ROOT));
    }
}
