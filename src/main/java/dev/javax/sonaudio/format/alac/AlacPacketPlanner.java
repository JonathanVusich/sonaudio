package dev.javax.sonaudio.format.alac;

import dev.javax.sonaudio.exception.InvalidFormatException;
import java.util.BitSet;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

/**
 * Picks the packets of an ALAC track that FFmpeg's MOV demuxer returns, and works out how it trims them. It lays the
 * samples out as its index of them is, then arranges that index by the track's edit list: each edit keeps the packets
 * that start in it, and the one that straddles its start, and FFmpeg decodes but drops those just before it. The
 * track's start then drops the packets before its first edit and the part of the packet it starts in, and its end,
 * which is where the track's duration or an {@code iTunSMPB} tag puts it, drops the padding after.
 *
 * <p>A planner works out one track's packets, once.
 */
final class AlacPacketPlanner {

    private static final int KEPT = 0;
    private static final int DROPPED = 1;

    private final Track.Alac track;
    private final long movieTimeScale;
    private final SampleIndex index;
    private final IntStream.Builder samples = IntStream.builder();
    private final LongStream.Builder timestamps = LongStream.builder();
    private final IntStream.Builder droppedFlags = IntStream.builder();
    private final LongStream.Builder durations = LongStream.builder();
    // Where the entries of the edit list end, in the track's time scale, which the track's duration is held to.
    private long editsEnd;
    private long initialSkip;
    private int nonEmptyEdits;
    // FFmpeg gives each entry the duration of the next sample of its time table, which it steps through as it adds
    // entries, from the first, whichever samples they are.
    private int timeCursor;

    AlacPacketPlanner(final Track.Alac track, final long movieTimeScale) throws InvalidFormatException {
        this.track = track;
        this.movieTimeScale = movieTimeScale;
        this.index = track.table().index();
    }

    /**
     * @param gaplessTags what the file's {@code iTunSMPB} tags say, in the order the file has them
     * @return the packets FFmpeg returns, and what it trims of them
     */
    AlacPackets plan(final List<Gapless> gaplessTags) {
        final var edits = track.edits();
        final var duration = editedTrack(edits);
        final var sampleRate = track.cookie().sampleRate();
        final var picked = samples.build().toArray();
        final var pickedTimes = timestamps.build().toArray();
        final var entryDurations = entryDurations(pickedTimes, duration);
        final var window = discardWindow(gaplessTags, pickedTimes, entryDurations, duration);
        final var offsets = new long[picked.length];
        final var sizes = new int[picked.length];
        final var nominal = new long[picked.length];
        final var paddings = new int[picked.length];
        final var dropped = new BitSet(picked.length);
        final var droppedValues = droppedFlags.build().toArray();
        for (var entry = 0; entry < picked.length; entry++) {
            final var sample = picked[entry];
            offsets[entry] = index.offset(sample);
            sizes[entry] = index.size(sample);
            nominal[entry] = AlacUtils.rescale(index.duration(sample), sampleRate, track.timeScale());
            dropped.set(entry, droppedValues[entry] == DROPPED);
            if (window.appliesTo(entry, picked.length)) {
                paddings[entry] = window.padding(pickedTimes[entry], entryDurations[entry]);
            }
        }
        return new AlacPackets(offsets, sizes, nominal, dropped, paddings, initialSkip);
    }

    // An entry without a duration lasts until the next starts, or until the track's duration for the last.
    private long[] entryDurations(final long[] pickedTimes, final long duration) {
        final var entryDurations = durations.build().toArray();
        for (var entry = 0; entry < entryDurations.length; entry++) {
            if (entryDurations[entry] == 0) {
                final var next = nextTime(pickedTimes, entry, duration);
                if (next >= pickedTimes[entry]) {
                    entryDurations[entry] = next - pickedTimes[entry];
                }
            }
        }
        return entryDurations;
    }

    private long nextTime(final long[] pickedTimes, final int entry, final long duration) {
        if (entry + 1 < pickedTimes.length) {
            return pickedTimes[entry + 1];
        }
        return duration;
    }

    // Arranges the samples by the edit list, and works out the track's duration, as FFmpeg's stream gets it.
    private long editedTrack(final List<Edit> edits) {
        var duration = track.duration();
        final var total = track.table().totalDuration();
        if (total != 0) {
            duration = Math.min(duration, total);
        }
        if (edits.isEmpty() || index.count() == 0) {
            for (var sample = 0; sample < index.count(); sample++) {
                add(sample, index.timestamp(sample), KEPT);
            }
            return duration;
        }
        for (final var edit : edits) {
            walkEdit(edit);
        }
        return Math.min(duration, editsEnd);
    }

    private void walkEdit(final Edit edit) {
        final var length = AlacUtils.rescale(edit.duration(), track.timeScale(), movieTimeScale);
        final var counter = editsEnd;
        editsEnd += length;
        // Empty edits before the first of the track's media only delay it, which FFmpeg's decoded audio doesn't show.
        switch (edit) {
            case Edit.Empty _ -> {
                if (nonEmptyEdits == 0) {
                    return;
                }
            }
            case Edit.Media _ -> {}
        }
        nonEmptyEdits++;
        final var mediaTime = edit.mediaTime();
        // An audio decoder wouldn't need a second of lead-in for ALAC, but FFmpeg starts that early all the same.
        final var from = Math.max(mediaTime - track.timeScale(), index.timestamp(0));
        walkSamples(index.lastStartingAtOrBefore(from), mediaTime, length, counter);
    }

    private void walkSamples(final int firstSample, final long mediaTime, final long length, final long start) {
        final var end = length + mediaTime;
        var counter = start;
        var started = false;
        final var first = nonEmptyEdits == 1;
        for (var sample = firstSample; sample < index.count(); sample++) {
            final var frameDuration = frameDuration(sample, length);
            final var time = index.timestamp(sample);
            var status = KEPT;
            if (time < mediaTime || time >= end) {
                if (time < mediaTime && time + frameDuration > mediaTime && first) {
                    initialSkip += mediaTime - time;
                    counter -= mediaTime - time;
                    started = true;
                } else {
                    status = DROPPED;
                    if (!started && first) {
                        initialSkip += frameDuration;
                    }
                }
            } else {
                started = true;
            }
            add(sample, counter, status);
            if (started) {
                counter += frameDuration;
            }
            if (time + frameDuration >= end) {
                return;
            }
        }
    }

    private long frameDuration(final int sample, final long editLength) {
        if (sample + 1 < index.count()) {
            return index.timestamp(sample + 1) - index.timestamp(sample);
        }
        return editLength;
    }

    // The status is KEPT or DROPPED, which a packet's flag in the dropped flags is.
    private void add(final int sample, final long timestamp, final int status) {
        samples.add(sample);
        timestamps.add(timestamp);
        droppedFlags.add(status);
        if (timeCursor < Math.min(track.table().timedSamples(), index.count())) {
            durations.add(index.duration(timeCursor));
        } else {
            durations.add(0);
        }
        timeCursor++;
    }

    // The last tag that fits the track sets the window as the file is read, and a tag that doesn't leaves it to the
    // last packet, which sets it as it is returned.
    private DiscardWindow discardWindow(
            final List<Gapless> gaplessTags,
            final long[] pickedTimes,
            final long[] entryDurations,
            final long duration) {
        for (final var gapless : gaplessTags.reversed()) {
            final var window = smpbWindow(gapless, duration);
            if (window.first() != 0) {
                return window;
            }
        }
        return lastPacketWindow(pickedTimes, entryDurations, duration);
    }

    // iTunes' tag puts the end of the audio where its remainder starts, when it fits in the track. A window that
    // starts at 0 is one that the tag didn't set.
    private DiscardWindow smpbWindow(final Gapless gapless, final long duration) {
        final var sampleRate = track.cookie().sampleRate();
        final var timeScale = track.timeScale();
        final var total = AlacUtils.rescale(duration, sampleRate, timeScale);
        final var padding = AlacUtils.rescale(gapless.remainder(), sampleRate, timeScale);
        final var audio = AlacUtils.rescale(gapless.samples(), sampleRate, timeScale);
        if (gapless.remainder() > 0 && total > padding && total > audio) {
            return new DiscardWindow(sampleRate, timeScale, total - padding, total, DiscardWindow.Scope.EVERY_PACKET);
        }
        return new DiscardWindow(sampleRate, timeScale, 0, 0, DiscardWindow.Scope.EVERY_PACKET);
    }

    // Without the tag, the end of the last packet is trimmed to the track's duration.
    private DiscardWindow lastPacketWindow(final long[] pickedTimes, final long[] entryDurations, final long duration) {
        final var sampleRate = track.cookie().sampleRate();
        final var timeScale = track.timeScale();
        if (pickedTimes.length == 0) {
            return new DiscardWindow(sampleRate, timeScale, 0, 0, DiscardWindow.Scope.LAST_PACKET);
        }
        final var last = pickedTimes.length - 1;
        final var time = pickedTimes[last];
        var length = 0L;
        if (duration >= time) {
            length = Math.min(entryDurations[last], duration - time);
        }
        final var first =
                AlacUtils.rescale(time, sampleRate, timeScale) + AlacUtils.rescale(length, sampleRate, timeScale);
        final var total = AlacUtils.rescale(duration, sampleRate, timeScale);
        return new DiscardWindow(sampleRate, timeScale, first, total, DiscardWindow.Scope.LAST_PACKET);
    }
}
