package dev.javax.sonaudio.format.alac;

/**
 * What iTunes' {@code iTunSMPB} tag says of the padding at the end of a track.
 */
sealed interface Gapless permits Gapless.Absent, Gapless.Smpb {

    /**
     * No {@code iTunSMPB} tag, or one that FFmpeg doesn't read.
     */
    record Absent() implements Gapless {}

    /**
     * An {@code iTunSMPB} tag of three numbers.
     *
     * @param remainder how many samples of padding end the track
     * @param samples how many samples of audio the track has
     */
    record Smpb(int remainder, int samples) implements Gapless {}
}
