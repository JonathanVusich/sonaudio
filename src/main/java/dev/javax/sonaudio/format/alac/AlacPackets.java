package dev.javax.sonaudio.format.alac;

import java.util.BitSet;

/**
 * The packets of an ALAC track in the order FFmpeg's demuxer returns them, once the track's edit list has picked them:
 * where each is, whether it plays, and how many samples of it the track's start and end trimming drop. It holds arrays
 * that nothing changes once they're made.
 */
final class AlacPackets {

    private final long[] offsets;
    private final int[] sizes;
    private final long[] nominalSamples;
    private final BitSet dropped;
    private final int[] endPaddings;
    private final long initialSkip;

    /**
     * @param offsets the file position of each packet, which the packets keep: the caller made it for them
     * @param sizes the length in bytes of each packet
     * @param nominalSamples how many samples per channel each packet should decode to, as its time says
     * @param dropped the packets that FFmpeg decodes but drops, as they are outside the edit list
     * @param endPaddings how many samples at the end of each packet the track's end drops
     * @param initialSkip how many samples the track's start drops: the packets before the edit and the part of one
     *     that it starts in
     */
    AlacPackets(
            final long[] offsets,
            final int[] sizes,
            final long[] nominalSamples,
            final BitSet dropped,
            final int[] endPaddings,
            final long initialSkip) {
        this.offsets = offsets;
        this.sizes = sizes;
        this.nominalSamples = nominalSamples;
        this.dropped = dropped;
        this.endPaddings = endPaddings;
        this.initialSkip = initialSkip;
    }

    int count() {
        return offsets.length;
    }

    long offset(final int packet) {
        return offsets[packet];
    }

    int size(final int packet) {
        return sizes[packet];
    }

    /**
     * @return how many samples the start of the track drops, before any packet is decoded
     */
    long initialSkip() {
        return initialSkip;
    }

    /**
     * Works out what plays of a decoded packet, as FFmpeg's decoder trims its frames: a dropped packet takes from what
     * is still to be skipped, a packet that is skipped wholly or partly is cut at its start, and the padding is cut
     * from its end, unless that would be more than is left.
     *
     * @param packet the packet
     * @param samples how many samples per channel it decoded to
     * @param pendingSkip how many samples of the packets so far are still to be skipped
     * @return which of the packet's samples play, and what is still to be skipped after it
     */
    PacketTrim trim(final int packet, final int samples, final long pendingSkip) {
        if (dropped.get(packet)) {
            return new PacketTrim(0, 0, Math.max(0, pendingSkip - samples));
        }
        if (pendingSkip >= samples && pendingSkip > 0) {
            return new PacketTrim(0, 0, pendingSkip - samples);
        }
        final var first = (int) pendingSkip;
        final var remaining = samples - first;
        final var padding = endPaddings[packet];
        if (padding > 0 && padding <= remaining) {
            return new PacketTrim(first, remaining - padding, 0);
        }
        return new PacketTrim(first, remaining, 0);
    }

    /**
     * @return how many samples per channel the packets play if each decodes to as many samples as its time says
     */
    long nominalSampleCount() {
        var total = 0L;
        var pendingSkip = initialSkip;
        for (var packet = 0; packet < count(); packet++) {
            final var trim = trim(packet, (int) nominalSamples[packet], pendingSkip);
            total += trim.count();
            pendingSkip = trim.pendingSkip();
        }
        return total;
    }
}
