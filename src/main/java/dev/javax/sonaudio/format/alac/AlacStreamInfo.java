package dev.javax.sonaudio.format.alac;

import dev.javax.sonaudio.format.AudioUtils;
import java.time.Duration;

/**
 * What an ALAC file's header says of its audio: how it's coded, and how much of it plays once FFmpeg has applied the
 * file's edit list and trimmed the last packet to the track's duration.
 *
 * @param cookie the decoder's configuration, from the sample entry
 * @param sampleCount samples per channel that play, as the file's timing says: every packet is counted as long as the
 *     time it's given, which the audio of a well-formed file matches
 */
public record AlacStreamInfo(AlacCookie cookie, long sampleCount) {

    /**
     * The duration the header gives, which {@link AudioUtils#measureDuration} checks against the packets themselves.
     *
     * @return how long the audio plays
     */
    public Duration duration() {
        return AudioUtils.duration(sampleCount, cookie.sampleRate());
    }
}
