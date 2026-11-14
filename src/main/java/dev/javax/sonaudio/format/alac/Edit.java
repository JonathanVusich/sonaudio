package dev.javax.sonaudio.format.alac;

/**
 * One entry of a track's edit list, which says which part of its media plays next.
 */
sealed interface Edit permits Edit.Empty, Edit.Media {

    /**
     * @return how long the entry plays, in the movie's time scale
     */
    long duration();

    /**
     * @return where in the track's media the entry starts, in the track's time scale: -1 for an empty edit, as the
     *     format encodes it
     */
    long mediaTime();

    /**
     * Silence before the track's media starts.
     *
     * @param duration how long it lasts, in the movie's time scale
     */
    record Empty(long duration) implements Edit {

        @Override
        public long mediaTime() {
            return -1;
        }
    }

    /**
     * A stretch of the track's media.
     *
     * @param duration how long it plays, in the movie's time scale
     * @param mediaTime where it starts, in the track's time scale
     */
    record Media(long duration, long mediaTime) implements Edit {}
}
