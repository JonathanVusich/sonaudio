package dev.javax.sonaudio.format.alac;

import dev.javax.sonaudio.exception.InvalidFormatException;
import java.util.List;

/**
 * Collects what the parser reads from a {@code trak} box, to make a {@link Track} once all of it has been read.
 */
final class TrackBuilder {

    /**
     * What a track's {@code hdlr} box says it holds.
     */
    enum Handler {
        SOUND,
        VISUAL,
        OTHER
    }

    private static final String ALAC_FORMAT = "alac";

    private Handler handler = Handler.OTHER;
    private long timeScale;
    private long duration;
    private int entryCount;
    private String entryFormat = "";
    private AlacCookie cookie;
    private SampleTable table;
    private List<Edit> edits = List.of();

    void setHandler(final Handler handler) {
        this.handler = handler;
    }

    void setMediaHeader(final long timeScale, final long duration) {
        this.timeScale = timeScale;
        this.duration = duration;
    }

    void setSampleEntries(final int entryCount, final String entryFormat) {
        this.entryCount = entryCount;
        this.entryFormat = entryFormat;
    }

    /**
     * @return whether the track has an ALAC cookie yet
     */
    boolean hasCookie() {
        return cookie != null;
    }

    void setCookie(final AlacCookie cookie) {
        this.cookie = cookie;
    }

    void setSampleTable(final SampleTable table) {
        this.table = table;
    }

    void setEdits(final List<Edit> edits) {
        this.edits = edits;
    }

    /**
     * Makes the track. A track is audio if its handler says sound, or if its handler isn't a visual one and its sample
     * entry is ALAC, as FFmpeg tells it.
     *
     * @return the track, of ALAC, of another audio codec, or not audio at all
     * @throws InvalidFormatException when an ALAC track is missing a part it can't be decoded without
     */
    Track build() throws InvalidFormatException {
        final var audio = handler == Handler.SOUND || (handler == Handler.OTHER && entryFormat.equals(ALAC_FORMAT));
        if (!audio) {
            return new Track.NotAudio();
        }
        if (!entryFormat.equals(ALAC_FORMAT)) {
            return new Track.OtherAudio(entryFormat);
        }
        return buildAlac();
    }

    private Track.Alac buildAlac() throws InvalidFormatException {
        if (entryCount != 1) {
            throw new InvalidFormatException("ALAC track has " + entryCount + " sample descriptions, not 1!");
        }
        if (cookie == null) {
            throw new InvalidFormatException("ALAC sample entry has no alac box!");
        }
        if (timeScale <= 0) {
            throw new InvalidFormatException("ALAC track has no mdhd box, or a time scale of 0!");
        }
        if (table == null) {
            throw new InvalidFormatException("ALAC track has no sample tables!");
        }
        return new Track.Alac(cookie, timeScale, duration, table, edits);
    }
}
