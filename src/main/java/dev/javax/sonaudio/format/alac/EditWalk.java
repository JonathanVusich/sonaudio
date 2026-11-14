package dev.javax.sonaudio.format.alac;

/**
 * The entries that FFmpeg's index has for some of a track's edits: which sample each is, when it plays, and whether it
 * is only decoded to be dropped.
 *
 * @param samples the sample of each entry, which the walk keeps: the planner made it for the walk
 * @param times when each entry plays, in the track's time scale
 * @param dropped 1 for an entry that is dropped, and 0 for one that plays
 * @param skip how many samples at the start of the audio the edits drop, as FFmpeg counts them
 * @param editsEnd where the last of the edits ends, in the track's time scale
 */
record EditWalk(int[] samples, long[] times, int[] dropped, long skip, long editsEnd) {

    static final int KEPT = 0;
    static final int DROPPED = 1;

    /**
     * @param next the walk of the edits after these
     * @return the walk of both, whose entries are these and then the next's
     */
    EditWalk followedBy(final EditWalk next) {
        final var count = samples.length + next.samples().length;
        final var allSamples = new int[count];
        final var allTimes = new long[count];
        final var allDropped = new int[count];
        System.arraycopy(samples, 0, allSamples, 0, samples.length);
        System.arraycopy(times, 0, allTimes, 0, samples.length);
        System.arraycopy(dropped, 0, allDropped, 0, samples.length);
        System.arraycopy(next.samples(), 0, allSamples, samples.length, next.samples().length);
        System.arraycopy(next.times(), 0, allTimes, samples.length, next.samples().length);
        System.arraycopy(next.dropped(), 0, allDropped, samples.length, next.samples().length);
        return new EditWalk(allSamples, allTimes, allDropped, skip + next.skip(), next.editsEnd());
    }
}
