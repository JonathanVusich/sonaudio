package dev.javax.sonaudio.format.opus;

/**
 * Which samples of a decoded frame are left once the decoder has dropped the pre-skip and the end of the stream.
 */
sealed interface SampleRange {

    /**
     * @param from the index of the first sample kept
     * @param count how many samples are kept, from 1
     */
    record Kept(int from, int count) implements SampleRange {}

    /**
     * Every sample of the frame is dropped, so the frame is too.
     */
    record Dropped() implements SampleRange {}
}
