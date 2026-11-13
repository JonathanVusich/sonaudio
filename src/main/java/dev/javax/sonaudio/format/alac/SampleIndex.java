package dev.javax.sonaudio.format.alac;

/**
 * Where each sample of a track, which is one ALAC packet, is in the file, and when it plays: what the sample tables
 * make of them, before the track's edit list is applied. It holds arrays that nothing changes once they're made.
 */
final class SampleIndex {

    private final long[] offsets;
    private final int[] sizes;
    private final long[] timestamps;
    private final long[] durations;

    /**
     * @param offsets the file position of each sample, which the index keeps: the caller made it for the index
     * @param sizes the length in bytes of each sample
     * @param timestamps the decoding time of each sample, in the track's time scale
     * @param durations how long each sample lasts, in the track's time scale
     */
    SampleIndex(final long[] offsets, final int[] sizes, final long[] timestamps, final long[] durations) {
        this.offsets = offsets;
        this.sizes = sizes;
        this.timestamps = timestamps;
        this.durations = durations;
    }

    int count() {
        return offsets.length;
    }

    long offset(final int sample) {
        return offsets[sample];
    }

    int size(final int sample) {
        return sizes[sample];
    }

    long timestamp(final int sample) {
        return timestamps[sample];
    }

    long duration(final int sample) {
        return durations[sample];
    }

    /**
     * @param time a time in the track's time scale, which isn't before the first sample's
     * @return the first sample that starts at the last timestamp not after the time
     */
    int lastStartingAtOrBefore(final long time) {
        var low = 0;
        var high = timestamps.length - 1;
        while (low < high) {
            final var middle = (low + high + 1) >>> 1;
            if (timestamps[middle] <= time) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        while (low > 0 && timestamps[low] == timestamps[low - 1]) {
            low--;
        }
        return low;
    }
}
