package dev.javax.sonaudio.format.opus;

import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.opus.NextPage.Found;
import dev.javax.sonaudio.format.opus.NextPage.NoMorePages;
import dev.javax.sonaudio.format.opus.PacketTime.Known;
import dev.javax.sonaudio.format.opus.PacketTime.Unknown;
import dev.javax.sonaudio.format.opus.PageGranule.Positioned;
import dev.javax.sonaudio.format.opus.PageGranule.Unpositioned;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.NoSuchElementException;

/**
 * One logical Opus stream of an Ogg file, read a packet at a time: it takes the pages of its serial number from the
 * file's stream, puts a packet that crosses pages back together, and gives each audio packet the trimming FFmpeg's
 * Ogg demuxer gives it.
 *
 * <p>The pages of any other logical stream are skipped, so a multiplexed file decodes its first Opus stream, as
 * FFmpeg's command line does for the stream it picks. A page that starts a new stream once audio has begun is a
 * chained file, which FFmpeg decodes as one stream with new headers and this throws for.
 *
 * <p>The trimming follows FFmpeg's {@code oggparseopus.c}. A page's granule position says where its last packet ends,
 * so each packet starts where the page before ended, or, for the first page and any page after one with a granule
 * position of 0, as far before this page's granule position as the packets that end on it are long. The packets of a
 * page that ends the stream are timed one after another from there instead, and the packet that carries the stream past
 * the page's granule position has the excess as its end trim.
 *
 * <p>Two of its results come from how FFmpeg times a stream. Only the packet that carries the stream past the last
 * page's granule position gets an end trim, and the decoder applies one only when it is less than the samples the
 * packet's frame has once the pre-skip is dropped: a trim of exactly that many drops the frame, and a narrowband
 * packet whose frame is 24 samples short of its duration (see {@link OpusStream}) may not be trimmed at all. And when
 * all the packets of a stream are on one page, which ends it, FFmpeg can't tell where the stream started from the
 * granule position, so it times them from 0 and not from the granule position less their duration and the pre-skip;
 * this does the same.
 */
final class OggStream {

    private static final long MAX_GRANULE = 1L << 62;

    private final OpusParser parser;
    private final InputStream input;
    private final int serial;
    private final int preSkip;
    private final Deque<byte[]> readyPackets = new ArrayDeque<>();
    // The start of a packet that goes on in the next page.
    private byte[] carried = new byte[0];
    // What the page that the ready packets ended on says.
    private PageEnd pageEnd = PageEnd.MORE;
    private PageGranule pageGranule = new Unpositioned();
    // Once audio has begun, a page that starts another stream means a chained file.
    private OggPhase phase = OggPhase.HEADERS;
    // FFmpeg's pts for the next packet, which starts at 0 as its stream's does, and its dts for the packet's end.
    private PacketTime nextStart = new Known(0);
    private long decodeTime;
    private int pendingStartTrim;

    /**
     * @param parser reads the pages
     * @param input the file, at a page, which this reads to its end
     * @param serial the serial number of the logical stream to read
     * @param preSkip how many samples the first audio packet has the decoder drop from its output
     */
    OggStream(final OpusParser parser, final InputStream input, final int serial, final int preSkip) {
        this.parser = parser;
        this.input = input;
        this.serial = serial;
        this.preSkip = preSkip;
        this.pendingStartTrim = preSkip;
    }

    /**
     * @return whether the stream has another packet, reading pages to find out
     * @throws IOException when the file can't be read
     * @throws InvalidFormatException when a page is malformed
     */
    boolean hasNextPacket() throws IOException, InvalidFormatException {
        while (readyPackets.isEmpty()) {
            if (!loadNextPage()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads a packet of the headers (RFC 7845 section 3), which have no timing.
     *
     * @return the packet
     * @throws IOException when the file can't be read
     * @throws InvalidFormatException when the stream has no more packets, or a page is malformed
     */
    byte[] nextHeaderPacket() throws IOException, InvalidFormatException {
        if (!hasNextPacket()) {
            throw new InvalidFormatException("Ogg Opus stream ends before its headers do");
        }
        return readyPackets.pollFirst();
    }

    /**
     * Checks that the headers have ended with their page, as RFC 7845 requires, so the audio starts on a fresh one.
     *
     * @throws InvalidFormatException when a packet of the headers is followed by another on the same page
     */
    void requireAudioOnFreshPage() throws InvalidFormatException {
        if (!readyPackets.isEmpty() || carried.length > 0) {
            throw new InvalidFormatException("Opus audio doesn't start on a page of its own");
        }
    }

    /**
     * @return the next audio packet, with the samples to drop from what it decodes to
     * @throws NoSuchElementException when {@link #hasNextPacket} is false
     * @throws InvalidFormatException when the packet is empty, or has code 3 and nothing after its TOC byte
     */
    OggPacket nextAudioPacket() throws InvalidFormatException {
        phase = OggPhase.AUDIO;
        final var bytes = readyPackets.pollFirst();
        if (bytes == null) {
            throw new NoSuchElementException("Opus stream has no more packets!");
        }
        if (bytes.length == 0) {
            throw new InvalidFormatException("Ogg Opus packet is empty");
        }
        final var duration = new OggPacket(bytes, 0, 0).duration();
        synchronizeTime(duration);
        decodeTime += duration;
        final var endTrim = endTrim(duration);
        final var startTrim = pendingStartTrim;
        pendingStartTrim = 0;
        followPageGranule();
        return new OggPacket(bytes, startTrim, endTrim);
    }

    private boolean loadNextPage() throws IOException, InvalidFormatException {
        while (true) {
            switch (parser.readPage(input)) {
                case NoMorePages _ -> {
                    return endOfFile();
                }
                case Found(final var page) -> {
                    if (page.serial() == serial) {
                        acceptPage(page);
                        return true;
                    }
                    skipOtherStream(page);
                }
            }
        }
    }

    private boolean endOfFile() throws InvalidFormatException {
        if (carried.length > 0) {
            throw new InvalidFormatException("Ogg file ends inside a packet");
        }
        return false;
    }

    private void skipOtherStream(final OggPage page) throws InvalidFormatException {
        if (page.start() == PageStart.BEGINNING && phase == OggPhase.AUDIO) {
            throw new InvalidFormatException("Chained Ogg Opus streams are not supported");
        }
    }

    private void acceptPage(final OggPage page) throws InvalidFormatException {
        final var continued = carried.length > 0;
        if (continued != (page.continuation() == PageContinuation.CONTINUED)) {
            throw new InvalidFormatException("Ogg page's continuation flag disagrees with the packet before it");
        }
        if (page.granule() instanceof Positioned(final var samples) && (samples < 0 || samples > MAX_GRANULE)) {
            throw new InvalidFormatException("Unsupported huge Ogg granule position: " + samples);
        }
        for (final var fragment : page.fragments()) {
            joinFragment(fragment);
        }
        pageEnd = page.end();
        pageGranule = page.granule();
    }

    private void joinFragment(final PacketFragment fragment) {
        final var joined = new ByteArrayOutputStream();
        joined.writeBytes(carried);
        joined.writeBytes(fragment.bytes());
        carried = joined.toByteArray();
        if (fragment.end() == FragmentEnd.COMPLETES_PACKET) {
            readyPackets.addLast(carried);
            carried = new byte[0];
        }
    }

    // FFmpeg takes a packet's start from the page before it when that gave one, and otherwise works it back from this
    // page's granule position, which on the page that ends the stream it doesn't.
    private void synchronizeTime(final int duration) {
        if (pageEnd == PageEnd.MORE && needsTimeFromGranule()) {
            if (pageGranule instanceof Positioned(final var granule)) {
                nextStart = new Known(granule - duration - readyDuration());
            }
        }
        if (nextStart instanceof Known(final var start)) {
            decodeTime = start - preSkip;
        }
    }

    private boolean needsTimeFromGranule() {
        return switch (nextStart) {
            case Unknown _ -> true;
            case Known(final var position) -> position == 0;
        };
    }

    // FFmpeg adds the length of the packets that end after this one on the page, leaving out those it can't measure.
    private long readyDuration() {
        var total = 0L;
        for (final var packet : readyPackets) {
            total += measurableDuration(packet);
        }
        return total;
    }

    private int measurableDuration(final byte[] packet) {
        try {
            return Math.max(0, new OggPacket(packet, 0, 0).duration());
        } catch (final InvalidFormatException unmeasurable) {
            return 0;
        }
    }

    private int endTrim(final int duration) {
        if (pageEnd != PageEnd.LAST) {
            return 0;
        }
        return switch (pageGranule) {
            case Unpositioned _ -> 0;
            case Positioned(final var granule) -> {
                final var excess = decodeTime + preSkip - granule;
                yield (int) Math.max(0, Math.min(excess, duration));
            }
        };
    }

    // The next packet starts where the page ended when this packet was its last, as FFmpeg's ogg_calc_pts has it.
    private void followPageGranule() {
        nextStart = new Unknown();
        if (readyPackets.isEmpty() && pageGranule instanceof Positioned(final var granule)) {
            nextStart = new Known(granule);
        }
    }
}
