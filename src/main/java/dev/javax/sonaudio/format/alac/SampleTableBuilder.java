package dev.javax.sonaudio.format.alac;

/**
 * Collects the boxes of a track's {@code stbl} box that make its {@link SampleTable}, which are all empty until the
 * parser has read them.
 */
final class SampleTableBuilder {

    private long[] timeCounts = new long[0];
    private long[] timeDurations = new long[0];
    private int[] firstChunks = new int[0];
    private int[] chunkSamples = new int[0];
    private int[] chunkDescriptions = new int[0];
    private int fixedSampleSize;
    private long sampleCount;
    private int[] sampleSizes = new int[0];
    private long[] chunkOffsets = new long[0];

    void setTimes(final long[] counts, final long[] durations) {
        this.timeCounts = counts;
        this.timeDurations = durations;
    }

    void setChunks(final int[] firstChunks, final int[] samples, final int[] descriptions) {
        this.firstChunks = firstChunks;
        this.chunkSamples = samples;
        this.chunkDescriptions = descriptions;
    }

    void setSizes(final int fixedSampleSize, final long sampleCount, final int[] sampleSizes) {
        this.fixedSampleSize = fixedSampleSize;
        this.sampleCount = sampleCount;
        this.sampleSizes = sampleSizes;
    }

    void setOffsets(final long[] chunkOffsets) {
        this.chunkOffsets = chunkOffsets;
    }

    SampleTable build() {
        return new SampleTable(
                timeCounts,
                timeDurations,
                firstChunks,
                chunkSamples,
                chunkDescriptions,
                fixedSampleSize,
                sampleCount,
                sampleSizes,
                chunkOffsets);
    }
}
