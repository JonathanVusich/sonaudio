package dev.javax.sonaudio.format.alac;

/**
 * The samples at the end of a track that FFmpeg's demuxer has the decoder drop: those from {@code first} on, which a
 * packet's end padding is counted from. A window that starts at sample 0 drops nothing.
 *
 * @param sampleRate the track's samples per second
 * @param timeScale the ticks per second of the track's times
 * @param first the first sample dropped, counted from the track's start
 * @param last the sample the window ends at
 * @param scope which packets the window drops samples of
 */
record DiscardWindow(int sampleRate, long timeScale, long first, long last, Scope scope) {

    /**
     * Which packets a window applies to.
     */
    enum Scope {
        /**
         * A window that an {@code iTunSMPB} tag set when the file was read applies to every packet.
         */
        EVERY_PACKET,
        /**
         * A window that FFmpeg sets as it returns the last packet only applies to that.
         */
        LAST_PACKET
    }

    /**
     * @param packet the index of a packet
     * @param packetCount how many packets there are
     * @return whether the window drops samples of the packet
     */
    boolean appliesTo(final int packet, final int packetCount) {
        return switch (scope) {
            case EVERY_PACKET -> true;
            case LAST_PACKET -> packet == packetCount - 1;
        };
    }

    /**
     * @param time when the packet plays, in the track's time scale
     * @param duration how long the packet plays, in the track's time scale
     * @return how many samples at the end of the packet the window drops
     */
    int padding(final long time, final long duration) {
        if (first == 0) {
            return 0;
        }
        final var start = AlacUtils.rescale(time, sampleRate, timeScale);
        final var length = AlacUtils.rescale(duration, sampleRate, timeScale);
        final var end = start + length;
        if (length > 0 && end >= first && start < last) {
            return (int) Math.min(Math.min(end - first, length), Integer.MAX_VALUE);
        }
        return 0;
    }
}
