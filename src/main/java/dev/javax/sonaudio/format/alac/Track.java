package dev.javax.sonaudio.format.alac;

import java.util.List;

/**
 * A track of an MPEG-4 file, of the kinds the ALAC parser tells apart.
 */
sealed interface Track permits Track.Alac, Track.OtherAudio, Track.NotAudio {

    /**
     * An audio track of ALAC.
     *
     * @param cookie the decoder's configuration, from the sample entry
     * @param timeScale the ticks per second the track's times are in
     * @param duration how long the track's media lasts, in its time scale, as {@code mdhd} gives it
     * @param table where the track's packets are, and when they play
     * @param edits the track's edit list, which is empty when it has none
     */
    record Alac(AlacCookie cookie, long timeScale, long duration, SampleTable table, List<Edit> edits)
            implements Track {}

    /**
     * An audio track of another codec.
     *
     * @param format the sample entry's four-character type, such as {@code mp4a}
     */
    record OtherAudio(String format) implements Track {}

    /**
     * A track of anything but audio, such as cover art or a chapter list.
     */
    record NotAudio() implements Track {}
}
