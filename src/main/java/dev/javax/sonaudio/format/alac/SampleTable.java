package dev.javax.sonaudio.format.alac;

import dev.javax.sonaudio.exception.InvalidFormatException;

/**
 * A track's sample tables, as the {@code stts}, {@code stsc}, {@code stsz} (or {@code stz2}) and {@code stco} (or
 * {@code co64}) boxes give them: how long each sample lasts, which chunk each is in, how large it is and where the
 * chunks start. It holds arrays that nothing changes once they're made.
 */
final class SampleTable {

    // FFmpeg refuses a sample of more than this many bytes.
    private static final int MAX_SAMPLE_SIZE = 0x3FFFFFFF;

    private final long[] timeCounts;
    private final long[] timeDurations;
    private final int[] firstChunks;
    private final int[] chunkSamples;
    private final int[] chunkDescriptions;
    private final int fixedSampleSize;
    private final long sampleCount;
    private final int[] sampleSizes;
    private final long[] chunkOffsets;

    /**
     * @param timeCounts how many samples each {@code stts} entry covers
     * @param timeDurations how long each sample of each {@code stts} entry lasts
     * @param firstChunks the first chunk, from 1, of each {@code stsc} entry
     * @param chunkSamples how many samples each chunk of each {@code stsc} entry has
     * @param chunkDescriptions the sample description each chunk of each {@code stsc} entry uses, from 1
     * @param fixedSampleSize the size every sample has, or 0 when {@code sampleSizes} gives each its own
     * @param sampleCount how many samples the track has, as {@code stsz} counts them
     * @param sampleSizes the size of each sample, when {@code fixedSampleSize} is 0
     * @param chunkOffsets the file position of each chunk
     */
    SampleTable(
            final long[] timeCounts,
            final long[] timeDurations,
            final int[] firstChunks,
            final int[] chunkSamples,
            final int[] chunkDescriptions,
            final int fixedSampleSize,
            final long sampleCount,
            final int[] sampleSizes,
            final long[] chunkOffsets) {
        this.timeCounts = timeCounts;
        this.timeDurations = timeDurations;
        this.firstChunks = firstChunks;
        this.chunkSamples = chunkSamples;
        this.chunkDescriptions = chunkDescriptions;
        this.fixedSampleSize = fixedSampleSize;
        this.sampleCount = sampleCount;
        this.sampleSizes = sampleSizes;
        this.chunkOffsets = chunkOffsets;
    }

    /**
     * @return how long all the samples last, as {@code stts} adds them up, in the track's time scale
     */
    long totalDuration() {
        var total = 0L;
        for (var entry = 0; entry < timeCounts.length; entry++) {
            total += timeCounts[entry] * timeDurations[entry];
        }
        return total;
    }

    /**
     * @return how many samples {@code stts} gives a duration, which a table that covers fewer than there are samples
     *     leaves the rest of
     */
    long timedSamples() {
        var total = 0L;
        for (final var count : timeCounts) {
            total += count;
        }
        return Math.min(total, sampleCount);
    }

    /**
     * Lays the samples out chunk by chunk, as FFmpeg's index of them is: a chunk's samples follow each other from its
     * offset, and a sample's time follows the one before it, each lasting as long as {@code stts} says, or as long as
     * the last it gives when it covers fewer.
     *
     * @return the position, size and time of every sample the chunks hold
     * @throws InvalidFormatException when the tables contradict each other
     */
    SampleIndex index() throws InvalidFormatException {
        checkChunkEntries();
        final var count = sampleCountOfChunks();
        final var offsets = new long[count];
        final var sizes = new int[count];
        final var timestamps = new long[count];
        final var durations = expandDurations(count);
        var sample = 0;
        var time = 0L;
        var entry = 0;
        for (var chunk = 0; chunk < chunkOffsets.length; chunk++) {
            while (entry + 1 < firstChunks.length && chunk + 1 == firstChunks[entry + 1]) {
                entry++;
            }
            var offset = chunkOffsets[chunk];
            for (var inChunk = 0; inChunk < chunkSamples[entry]; inChunk++) {
                final var size = sampleSize(sample);
                offsets[sample] = offset;
                sizes[sample] = size;
                timestamps[sample] = time;
                offset += size;
                time += durations[sample];
                sample++;
            }
        }
        return new SampleIndex(offsets, sizes, timestamps, durations);
    }

    private void checkChunkEntries() throws InvalidFormatException {
        if (firstChunks.length == 0 || chunkOffsets.length == 0 || timeCounts.length == 0) {
            throw new InvalidFormatException("ALAC track is missing its stts, stsc or stco box!");
        }
        for (var entry = 0; entry < firstChunks.length; entry++) {
            final var increasing = entry == 0 || firstChunks[entry] > firstChunks[entry - 1];
            if (!increasing || firstChunks[entry] < entry + 1 || chunkSamples[entry] < 1) {
                throw new InvalidFormatException("ALAC track's stsc box has an invalid entry!");
            }
            if (chunkDescriptions[entry] != 1) {
                throw new InvalidFormatException("ALAC track's stsc box uses a sample description it doesn't have!");
            }
        }
        if (firstChunks[firstChunks.length - 1] > chunkOffsets.length) {
            throw new InvalidFormatException("ALAC track's stsc box starts chunks that stco doesn't have!");
        }
    }

    private int sampleCountOfChunks() throws InvalidFormatException {
        var total = 0L;
        for (var chunk = 0; chunk < chunkOffsets.length; chunk++) {
            total += samplesInChunk(chunk);
        }
        if (total > sampleCount) {
            throw new InvalidFormatException("ALAC track's chunks hold more samples than stsz gives sizes for!");
        }
        if (total > Integer.MAX_VALUE) {
            throw new InvalidFormatException("ALAC track has too many samples!");
        }
        return (int) total;
    }

    private int samplesInChunk(final int chunk) {
        var entry = 0;
        while (entry + 1 < firstChunks.length && chunk + 1 >= firstChunks[entry + 1]) {
            entry++;
        }
        return chunkSamples[entry];
    }

    private int sampleSize(final int sample) throws InvalidFormatException {
        final var size = sizeOf(sample);
        if (size > MAX_SAMPLE_SIZE || size < 0) {
            throw new InvalidFormatException(
                    "ALAC track has a sample of " + Integer.toUnsignedString(size) + " bytes!");
        }
        return size;
    }

    private int sizeOf(final int sample) {
        if (fixedSampleSize > 0) {
            return fixedSampleSize;
        }
        return sampleSizes[sample];
    }

    private long[] expandDurations(final int count) {
        final var durations = new long[count];
        var sample = 0;
        var last = 0L;
        for (var entry = 0; entry < timeCounts.length; entry++) {
            final var end = Math.min(count, sample + timeCounts[entry]);
            for (; sample < end; sample++) {
                durations[sample] = timeDurations[entry];
                last = timeDurations[entry];
            }
        }
        for (; sample < count; sample++) {
            durations[sample] = last;
        }
        return durations;
    }
}
