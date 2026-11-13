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
 */
final class AlacPacketPlanner {

    /**
     * Where an edit is among the track's, which sets what FFmpeg does with it.
     */
    private enum EditPlace {
        // An empty edit before any media: it only delays the audio, which FFmpeg's decoded samples don't show.
        LEADING_EMPTY,
        // The first edit of the track's media, the only one that FFmpeg starts in the middle of a packet.
        FIRST_MEDIA,
        LATER
    }

    private final Track.Alac track;
    private final long movieTimeScale;
    private final SampleIndex index;

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
        final var walk = walkEdits(track.edits());
        final var duration = trackDuration(walk);
        final var entries = walk.samples().length;
        final var entryDurations = entryDurations(walk.times(), duration);
        final var window = discardWindow(gaplessTags, walk.times(), entryDurations, duration);
        final var offsets = new long[entries];
        final var sizes = new int[entries];
        final var nominal = new long[entries];
        final var paddings = new int[entries];
        final var dropped = new BitSet(entries);
        for (var entry = 0; entry < entries; entry++) {
            final var sample = walk.samples()[entry];
            offsets[entry] = index.offset(sample);
            sizes[entry] = index.size(sample);
            nominal[entry] =
                    AlacUtils.rescale(index.duration(sample), track.cookie().sampleRate(), track.timeScale());
            dropped.set(entry, walk.dropped()[entry] == EditWalk.DROPPED);
            if (window.appliesTo(entry, entries)) {
                paddings[entry] = window.padding(walk.times()[entry], entryDurations[entry]);
            }
        }
        return new AlacPackets(offsets, sizes, nominal, dropped, paddings, walk.skip());
    }

    // The track's duration as FFmpeg's stream gets it: the shortest of the media's, the time table's and the edits'.
    private long trackDuration(final EditWalk walk) {
        var duration = track.duration();
        final var total = track.table().totalDuration();
        if (total != 0) {
            duration = Math.min(duration, total);
        }
        if (track.edits().isEmpty() || index.count() == 0) {
            return duration;
        }
        return Math.min(duration, walk.editsEnd());
    }

    // Without an edit list every sample plays, at its own time.
    private EditWalk walkEdits(final List<Edit> edits) {
        if (edits.isEmpty() || index.count() == 0) {
            final var samples = IntStream.range(0, index.count()).toArray();
            final var times = new long[samples.length];
            for (var sample = 0; sample < times.length; sample++) {
                times[sample] = index.timestamp(sample);
            }
            return new EditWalk(samples, times, new int[samples.length], 0, 0);
        }
        var walk = new EditWalk(new int[0], new long[0], new int[0], 0, 0);
        var mediaEdits = 0;
        for (final var edit : edits) {
            final var place = editPlace(edit, mediaEdits);
            if (place != EditPlace.LEADING_EMPTY) {
                mediaEdits++;
            }
            walk = walk.followedBy(walkEdit(edit, place, walk.editsEnd()));
        }
        return walk;
    }

    private EditPlace editPlace(final Edit edit, final int mediaEditsBefore) {
        if (mediaEditsBefore > 0) {
            return EditPlace.LATER;
        }
        return switch (edit) {
            case Edit.Empty _ -> EditPlace.LEADING_EMPTY;
            case Edit.Media _ -> EditPlace.FIRST_MEDIA;
        };
    }

    private EditWalk walkEdit(final Edit edit, final EditPlace place, final long editStart) {
        final var length = AlacUtils.rescale(edit.duration(), track.timeScale(), movieTimeScale);
        final var editEnd = editStart + length;
        if (place == EditPlace.LEADING_EMPTY) {
            return new EditWalk(new int[0], new long[0], new int[0], 0, editEnd);
        }
        final var mediaTime = edit.mediaTime();
        // An audio decoder wouldn't need a second of lead-in for ALAC, but FFmpeg starts that early all the same.
        final var from = Math.max(mediaTime - track.timeScale(), index.timestamp(0));
        return walkSamples(index.lastStartingAtOrBefore(from), mediaTime, length, editStart, place);
    }

    // Goes through the samples from the first, until the first that ends at or after the edit's end. A sample before
    // the edit, or after it, is dropped, except for the one that the first edit starts in, of which the start is
    // skipped.
    private EditWalk walkSamples(
            final int firstSample,
            final long mediaTime,
            final long length,
            final long editStart,
            final EditPlace place) {
        final var samples = IntStream.builder();
        final var times = LongStream.builder();
        final var dropped = IntStream.builder();
        final var end = length + mediaTime;
        var counter = editStart;
        var skip = 0L;
        var started = false;
        for (var sample = firstSample; sample < index.count(); sample++) {
            final var frameDuration = frameDuration(sample, length);
            final var time = index.timestamp(sample);
            var status = EditWalk.KEPT;
            if (time < mediaTime || time >= end) {
                if (time < mediaTime && time + frameDuration > mediaTime && place == EditPlace.FIRST_MEDIA) {
                    skip += mediaTime - time;
                    counter -= mediaTime - time;
                    started = true;
                } else {
                    status = EditWalk.DROPPED;
                    if (!started && place == EditPlace.FIRST_MEDIA) {
                        skip += frameDuration;
                    }
                }
            } else {
                started = true;
            }
            samples.add(sample);
            times.add(counter);
            dropped.add(status);
            if (started) {
                counter += frameDuration;
            }
            if (time + frameDuration >= end) {
                break;
            }
        }
        return new EditWalk(
                samples.build().toArray(),
                times.build().toArray(),
                dropped.build().toArray(),
                skip,
                editStart + length);
    }

    private long frameDuration(final int sample, final long editLength) {
        if (sample + 1 < index.count()) {
            return index.timestamp(sample + 1) - index.timestamp(sample);
        }
        return editLength;
    }

    // FFmpeg gives each entry the duration of the next sample of its time table, which it steps through as it adds
    // entries, from the first, whichever samples they are. An entry without a duration lasts until the next starts, or
    // until the track's duration for the last.
    private long[] entryDurations(final long[] times, final long duration) {
        final var timed = (int) Math.min(track.table().timedSamples(), index.count());
        final var entryDurations = new long[times.length];
        for (var entry = 0; entry < times.length; entry++) {
            if (entry < timed) {
                entryDurations[entry] = index.duration(entry);
            }
            if (entryDurations[entry] == 0) {
                final var next = nextTime(times, entry, duration);
                if (next >= times[entry]) {
                    entryDurations[entry] = next - times[entry];
                }
            }
        }
        return entryDurations;
    }

    private long nextTime(final long[] times, final int entry, final long duration) {
        if (entry + 1 < times.length) {
            return times[entry + 1];
        }
        return duration;
    }

    // The last tag that fits the track sets the window as the file is read, and a tag that doesn't leaves it to the
    // last packet, which sets it as it is returned.
    private DiscardWindow discardWindow(
            final List<Gapless> gaplessTags, final long[] times, final long[] entryDurations, final long duration) {
        for (final var gapless : gaplessTags.reversed()) {
            final var window = smpbWindow(gapless, duration);
            if (window.first() != 0) {
                return window;
            }
        }
        return lastPacketWindow(times, entryDurations, duration);
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
    private DiscardWindow lastPacketWindow(final long[] times, final long[] entryDurations, final long duration) {
        final var sampleRate = track.cookie().sampleRate();
        final var timeScale = track.timeScale();
        if (times.length == 0) {
            return new DiscardWindow(sampleRate, timeScale, 0, 0, DiscardWindow.Scope.LAST_PACKET);
        }
        final var last = times.length - 1;
        final var time = times[last];
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
