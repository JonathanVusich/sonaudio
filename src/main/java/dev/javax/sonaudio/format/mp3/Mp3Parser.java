package dev.javax.sonaudio.format.mp3;

import dev.javax.bitstream.BitInputStream;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;
import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.Format;
import dev.javax.sonaudio.format.Parser;
import dev.javax.sonaudio.format.mp3.FirstFrame.AudioFrame;
import dev.javax.sonaudio.format.mp3.FirstFrame.VbrHeader;
import dev.javax.sonaudio.format.mp3.Id3Frame.TextFrame;
import dev.javax.sonaudio.format.mp3.Id3Frame.UniqueFileId;
import dev.javax.sonaudio.format.mp3.Id3Frame.UserTextFrame;
import dev.javax.sonaudio.metadata.RawMetadata;
import dev.javax.sonaudio.metadata.RawMetadataBuilder;

/**
 * Reads an MP3 straight from the file's stream through bit streams: its ID3v2 tag at its start, in version 2.3 or 2.4
 * (id3.org's ID3v2.3.0 and ID3v2.4.0 structure documents), the ID3v1 tag that may end it, and its length from its first
 * frame. Every field of the ID3v2 tag is whole bytes, so the bit stream holds no bits between fields, and the tag's
 * regions skip what Sona doesn't read.
 */
public final class Mp3Parser implements Parser {

    // "ID3" starts a tag's header, and "3DI" its footer.
    private static final long IDENTIFIER = 0x49_44_33;
    private static final long FOOTER_IDENTIFIER = 0x33_44_49;
    private static final int HEADER_SIZE = 10;
    private static final int FOOTER_SIZE = 10;
    private static final int FOOTER = 0x10;
    // A frame header's sync, its first 11 bits.
    private static final long SYNC = 0xFFE0_0000L;
    // FFmpeg's MP3 demuxer looks this far for the first frame followed by another of its stream.
    private static final int FIRST_FRAME_SEARCH = 64 * 1024;
    // How much of a file FFmpeg reads to find its streams.
    private static final int PROBE_SIZE = 5_000_000;

    // Picard's TXXX descriptions, and the names it gives the same tags in Vorbis comments, so unknown tags have the
    // same names whether they were read from an MP3 or a FLAC file.
    private static final Map<String, String> PICARD_USER_TEXT = Map.of(
            "MusicBrainz Artist Id", "musicbrainz_artistid",
            "MusicBrainz Album Id", "musicbrainz_albumid",
            "MusicBrainz Album Artist Id", "musicbrainz_albumartistid",
            "MusicBrainz Release Group Id", "musicbrainz_releasegroupid",
            "MusicBrainz Release Track Id", "musicbrainz_releasetrackid",
            "MusicBrainz Work Id", "musicbrainz_workid",
            "MusicBrainz Disc Id", "musicbrainz_discid",
            "MusicBrainz Album Type", "releasetype",
            "MusicBrainz Album Status", "releasestatus",
            "MusicBrainz Album Release Country", "releasecountry");
    // Picard keeps the recording's MBID in a UFID frame of this owner, and calls it MUSICBRAINZ_TRACKID in FLAC.
    private static final String MUSICBRAINZ_OWNER = "http://musicbrainz.org";
    private static final String MUSICBRAINZ_RECORDING_ID = "musicbrainz_trackid";

    // The longest a free-format frame can be: 640 kbit/s, the most LAME and mpg123 allow, at MPEG-2.5's lowest sample
    // rate, padded.
    private static final int MAX_FREE_FORMAT_LENGTH = 72 * 640_000 / 8000 + 1;
    // The header bits every frame of a free-format stream shares: the sync, version, layer, bitrate index (0) and
    // sample rate index.
    private static final long FREE_FORMAT_STREAM_BITS = 0xFFFE_FC00L;

    private final Id3v1Reader id3v1Reader = new Id3v1Reader();
    private final VbrHeaderReader vbrHeaderReader = new VbrHeaderReader();

    @Override
    public Format format() {
        return Format.MP3;
    }

    // The tags are read first, as they say where the audio starts and ends.
    @Override
    public ParsedMp3 parse(final SeekableByteChannel channel) throws IOException, InvalidFormatException {
        try {
            final var id3v1Tag = readId3v1Tag(channel);
            final var appendedTag = readAppendedTag(channel, channel.size() - id3v1Tag.size());
            final var leadingTags = readLeadingTags(channel);
            final var audioStart =
                    leadingTags.stream().mapToLong(Id3v2Tag::size).sum();
            final var audioEnd = channel.size() - id3v1Tag.size() - appendedTag.size();
            final var streamInfo = readStreamInfo(channel, audioStart, audioEnd);
            final var audio = new Mp3Decoder(this, streamInfo, audioFrom(channel, streamInfo.audioStart()));
            final var frames = mergeFrames(framesByPrecedence(appendedTag, leadingTags, id3v1Tag));
            return new ParsedMp3(rawMetadata(streamInfo.duration(), frames), audio);
        } catch (final EOFException cutShort) {
            throw new InvalidFormatException("MP3 is cut short!");
        }
    }

    /**
     * Reads the ID3v2 tags a file starts with. FFmpeg reads them one after another, each updating the last, as some
     * encoders write a second tag after the first.
     *
     * @return the tags in file order, none when the file starts with none
     */
    private List<Id3v2Tag> readLeadingTags(final SeekableByteChannel channel)
            throws IOException, InvalidFormatException {
        final var tags = new ArrayList<Id3v2Tag>();
        var position = 0L;
        for (var tag = readId3v2Tag(streamAt(channel, position));
                tag.size() > 0;
                tag = readId3v2Tag(streamAt(channel, position))) {
            tags.add(tag);
            position += tag.size();
        }
        return List.copyOf(tags);
    }

    // The appended tag updates the tags before the audio, the last of which updates the one before it, and ID3v1 only
    // fills in what they lack.
    private List<List<? extends Id3Frame>> framesByPrecedence(
            final Id3v2Tag appendedTag, final List<Id3v2Tag> leadingTags, final Id3v1Tag id3v1Tag) {
        final var tags = new ArrayList<List<? extends Id3Frame>>();
        tags.add(appendedTag.frames());
        for (final var tag : leadingTags.reversed()) {
            tags.add(tag.frames());
        }
        tags.add(id3v1Tag.frames());
        return List.copyOf(tags);
    }

    // A file shorter than the tag can't end with one.
    private Id3v1Tag readId3v1Tag(final SeekableByteChannel channel) throws IOException {
        final var tagStart = channel.size() - Id3v1Reader.TAG_SIZE;
        if (tagStart < 0) {
            return new Id3v1Tag(0, List.of());
        }
        return id3v1Reader.readTag(streamAt(channel, tagStart));
    }

    /**
     * Reads the ID3v2.4 tag that may follow the audio, whose footer ends it. An encoder that updates a file's tags
     * without rewriting the file appends one.
     *
     * @param end where the tag would end: the start of the ID3v1 tag, or the end of the file without one
     * @return the tag, which is empty, with a size of 0, when there's none
     */
    private Id3v2Tag readAppendedTag(final SeekableByteChannel channel, final long end)
            throws IOException, InvalidFormatException {
        final var footerStart = end - FOOTER_SIZE;
        if (footerStart < 0) {
            return new Id3v2Tag(0, List.of());
        }
        final var bits = BitInputStream.wrap(streamAt(channel, footerStart), ByteOrder.BIG_ENDIAN);
        if (bits.readBits(24) != FOOTER_IDENTIFIER) {
            return new Id3v2Tag(0, List.of());
        }
        // The footer repeats the header's version, revision and flags, then its body's size.
        bits.readBits(24);
        final var tagStart = footerStart - Id3Utils.readSynchsafeInt(bits) - HEADER_SIZE;
        return readFooterTag(channel, tagStart, end);
    }

    // The tag the footer gives the size of must start where the footer says, and end at the footer.
    private Id3v2Tag readFooterTag(final SeekableByteChannel channel, final long tagStart, final long end)
            throws IOException, InvalidFormatException {
        if (tagStart < 0) {
            throw new InvalidFormatException("ID3v2 footer gives a tag longer than the file!");
        }
        final var tag = readId3v2Tag(streamAt(channel, tagStart));
        if (tag.size() != end - tagStart) {
            throw new InvalidFormatException("ID3v2 footer doesn't end the tag it gives the size of!");
        }
        return tag;
    }

    // Buffered, as the readers' bit streams take a few bytes at a time.
    private InputStream streamAt(final SeekableByteChannel channel, final long position) throws IOException {
        return new BufferedInputStream(Channels.newInputStream(channel.position(position)));
    }

    /**
     * @param audioStart where the decoder starts: the stream's first frame, or bytes before it that aren't one
     * @return the file from there, which the decoder finds frames in, then zeros, which a frame the file cuts short is
     *     read as ending with, as FFmpeg's buffers are padded with zeros
     */
    private BoundedInputStream audioFrom(final SeekableByteChannel channel, final long audioStart) throws IOException {
        final var file = Channels.newInputStream(channel.position(audioStart));
        final var zeros = new ByteArrayInputStream(new byte[MAX_FREE_FORMAT_LENGTH]);
        final var padded = new SequenceInputStream(file, zeros);
        return bounded(new BufferedInputStream(padded), Long.MAX_VALUE);
    }

    private BoundedInputStream bounded(final InputStream input, final long length) {
        return new BoundedInputStream(input, length);
    }

    /**
     * Finds where the decoder starts, as FFmpeg's MP3 demuxer does, and works out how long the stream is from its VBR
     * header, or by counting its frames when it has none. A Layer III frame right at the start of the audio may hold a
     * VBR header, and is skipped when it does. Then any bytes before the first frame that's followed by another of its
     * stream are skipped, if the frame is in the first 64 KiB. FFmpeg can't decode free format, which Sona can when the
     * audio starts with a free-format Layer III frame.
     *
     * @param audioStart where the audio starts: the end of the ID3v2 tag, or 0 when the file has none
     * @param audioEnd where the audio ends: the start of the tags after it, or the end of the file
     */
    Mp3StreamInfo readStreamInfo(final SeekableByteChannel channel, final long audioStart, final long audioEnd)
            throws IOException, InvalidFormatException {
        final var vbrFrame = readVbrHeader(channel, audioStart, audioEnd);
        final var afterVbrHeader = vbrFrame.end();
        final var firstFrame = findFirstFrame(channel, afterVbrHeader, audioEnd);
        // FFmpeg would only take a file for MPEG audio when it finds frames, whatever its name says: within its probe
        // size, though not skipping the bytes before a frame that is further in than the first 64 KiB, so the decoder
        // looks for that frame from the start of the audio, and FFmpeg's decoder drops it.
        if (firstFrame instanceof NoFrameFound
                && headerAt(channel, afterVbrHeader, audioEnd) instanceof NoHeader
                && findFrameWithin(channel, afterVbrHeader, audioEnd, PROBE_SIZE) instanceof NoFrameFound) {
            throw new InvalidFormatException("MP3 has no frames of audio!");
        }
        final var start = decoderStart(firstFrame, afterVbrHeader);
        final var freeFormat = readFreeFormatLength(channel, start, audioEnd);
        final var audio = bounded(streamAt(channel, start), audioEnd - start);
        final var bits = BitInputStream.wrap(audio, ByteOrder.BIG_ENDIAN);
        if (!(findFrame(audio, bits, audioEnd - start, freeFormat) instanceof FoundFrame first)) {
            throw new InvalidFormatException("MP3 has no frames of audio!");
        }
        final var length = trustedLength(channel, audioStart, vbrFrame);
        final var frameCount =
                length.frameCount() > 0 ? length.frameCount() : countFrames(audio, bits, audioEnd - start, freeFormat);
        return new Mp3StreamInfo(
                start,
                audioEnd,
                first.header().sampleRate(),
                first.header().channelCount(),
                first.header().samplesPerFrame(),
                frameCount,
                length.encoderTrim(),
                freeFormat);
    }

    // A VBR header's frame holds no audio, so it's skipped when its header gives the frame or byte count. Without one,
    // the frame is a VBR header with neither count that ends where it starts.
    private VbrFrame readVbrHeader(final SeekableByteChannel channel, final long audioStart, final long audioEnd)
            throws IOException, InvalidFormatException {
        if (!(headerAt(channel, audioStart, audioEnd) instanceof HeaderFound found)) {
            return noVbrFrame(audioStart);
        }
        final var header = found.header();
        if (header.layer() != Layer.III || header.bitrate() == FrameHeader.FREE_FORMAT_BITRATE) {
            return noVbrFrame(audioStart);
        }
        final var frame = bounded(streamAt(channel, audioStart + FrameHeader.SIZE), header.frameLength());
        return switch (vbrHeaderReader.readFirstFrame(header, frame)) {
            case VbrHeader vbrHeader -> new VbrFrame(vbrHeader, audioStart + header.frameLength());
            case AudioFrame _ -> noVbrFrame(audioStart);
        };
    }

    private VbrFrame noVbrFrame(final long audioStart) {
        return new VbrFrame(new VbrHeader(0, 0, new EncoderTrim.None()), audioStart);
    }

    /**
     * @param end where the frame holding the VBR header ends
     */
    private record VbrFrame(VbrHeader header, long end) {}

    /**
     * FFmpeg doesn't trust the frame count of a VBR header whose byte count is well short of the file's, as the file
     * may be two joined, so it trims no padding from the end of one, though it still trims the encoder's delay.
     *
     * @return the VBR header, or one with no frame count when there's none, so the frames are counted
     */
    private VbrHeader trustedLength(final SeekableByteChannel channel, final long audioStart, final VbrFrame vbrFrame)
            throws IOException {
        final var fileSize = channel.size() - audioStart - FrameHeader.SIZE;
        return trustedLength(vbrFrame.header(), fileSize);
    }

    private VbrHeader trustedLength(final VbrHeader header, final long fileSize) {
        final var smaller = Math.min(fileSize, header.byteCount());
        if (header.byteCount() > 0 && fileSize > header.byteCount() && fileSize - header.byteCount() > smaller >> 4) {
            return new VbrHeader(0, header.byteCount(), header.encoderTrim().withoutPadding());
        }
        return header;
    }

    // Where the decoder starts: at the first frame found, if there is one, and otherwise right after the VBR header.
    private long decoderStart(final FrameLocation firstFrame, final long afterVbrHeader) {
        return switch (firstFrame) {
            case FrameAt frameAt -> frameAt.position();
            case NoFrameFound _ -> afterVbrHeader;
        };
    }

    /**
     * @return where the first frame of the first 64 KiB from the position starts that's followed by another frame of
     *     its stream (the same version, layer and sample rate), or a {@code NoFrameFound} when none is
     */
    private FrameLocation findFirstFrame(final SeekableByteChannel channel, final long from, final long audioEnd)
            throws IOException, InvalidFormatException {
        return findFrameWithin(channel, from, audioEnd, FIRST_FRAME_SEARCH);
    }

    // The same, in that many bytes from the position.
    private FrameLocation findFrameWithin(
            final SeekableByteChannel channel, final long from, final long audioEnd, final long window)
            throws IOException, InvalidFormatException {
        final var end = Math.min(from + window, audioEnd);
        for (var position = from; position < end; position++) {
            if (isFollowedByFrameOfStream(channel, position, audioEnd)) {
                return new FrameAt(position);
            }
        }
        return new NoFrameFound();
    }

    /**
     * Where a search for a frame ended.
     */
    private sealed interface FrameLocation permits FrameAt, NoFrameFound {}

    private record FrameAt(long position) implements FrameLocation {}

    private record NoFrameFound() implements FrameLocation {}

    private boolean isFollowedByFrameOfStream(
            final SeekableByteChannel channel, final long position, final long audioEnd) throws IOException {
        if (!(headerAt(channel, position, audioEnd) instanceof HeaderFound found)) {
            return false;
        }
        final var header = found.header();
        if (header.bitrate() == FrameHeader.FREE_FORMAT_BITRATE) {
            return false;
        }
        final var nextPosition = position + header.frameLength();
        return headerAt(channel, nextPosition, Long.MAX_VALUE) instanceof HeaderFound next
                && isSameStream(header, next.header());
    }

    private boolean isSameStream(final FrameHeader header, final FrameHeader next) {
        return header.version() == next.version()
                && header.layer() == next.layer()
                && header.sampleRate() == next.sampleRate();
    }

    // The frame header made of four bytes of the file, wherever they are, leaving the channel where it was.
    private HeaderAt headerAt(final SeekableByteChannel channel, final long position, final long audioEnd)
            throws IOException {
        if (position + FrameHeader.SIZE > Math.min(audioEnd, channel.size())) {
            return new NoHeader();
        }
        return headerOf(wordAt(channel, position));
    }

    /**
     * Whether four bytes make a frame header.
     */
    private sealed interface HeaderAt permits HeaderFound, NoHeader {}

    private record HeaderFound(FrameHeader header) implements HeaderAt {}

    private record NoHeader() implements HeaderAt {}

    private long wordAt(final SeekableByteChannel channel, final long position) throws IOException {
        final var restore = channel.position();
        try {
            return BitInputStream.wrap(Channels.newInputStream(channel.position(position)), ByteOrder.BIG_ENDIAN)
                    .readBits(32);
        } finally {
            channel.position(restore);
        }
    }

    /**
     * @return how long each frame of a free-format stream is but for its padding, when the audio starts with a
     *     free-format Layer III frame, otherwise 0. Its header gives no length: the next frame's sync does, and every
     *     frame of the stream has that length but for its padding.
     */
    private int readFreeFormatLength(final SeekableByteChannel channel, final long start, final long audioEnd)
            throws IOException, InvalidFormatException {
        if (!(headerAt(channel, start, audioEnd) instanceof HeaderFound found)) {
            return 0;
        }
        final var header = found.header();
        if (header.layer() != Layer.III || header.bitrate() != FrameHeader.FREE_FORMAT_BITRATE) {
            return 0;
        }
        final var streamBits = BitInputStream.wrap(streamAt(channel, start), ByteOrder.BIG_ENDIAN)
                        .readBits(32)
                & FREE_FORMAT_STREAM_BITS;
        final var searchStart = FrameHeader.SIZE + header.sideInfoSize();
        final var bits = BitInputStream.wrap(streamAt(channel, start + searchStart), ByteOrder.BIG_ENDIAN);
        return findNextFrame(bits, streamBits, start, audioEnd, searchStart) - header.padding();
    }

    // How far after the first frame's start the next one starts, looking a byte at a time from the given distance. A
    // stream of one frame runs to the end of the audio.
    private int findNextFrame(
            final BitInputStream bits,
            final long streamBits,
            final long audioStart,
            final long audioEnd,
            final int searchStart)
            throws IOException, InvalidFormatException {
        for (var length = searchStart; length <= MAX_FREE_FORMAT_LENGTH; length++) {
            if (audioStart + length + FrameHeader.SIZE > audioEnd) {
                return (int) (audioEnd - audioStart);
            }
            if ((bits.peekBits(32) & FREE_FORMAT_STREAM_BITS) == streamBits) {
                return length;
            }
            bits.readBits(8);
        }
        throw new InvalidFormatException(
                "Free-format MP3 frame at byte %d has no next frame to give its length!".formatted(audioStart));
    }

    // The frames the decoder decodes: those found as FFmpeg's parser finds them, but for those it drops.
    private long countFrames(
            final BoundedInputStream audio, final BitInputStream bits, final long audioLength, final int unpaddedLength)
            throws IOException, InvalidFormatException {
        var frameCount = 0L;
        while (findFrame(audio, bits, audioLength, unpaddedLength) instanceof FoundFrame found) {
            bits.readBits(32);
            skipAudio(audio, found.header().frameLength() - FrameHeader.SIZE);
            if (found.lead() == FrameLead.AFTER_ZEROS) {
                frameCount++;
            }
        }
        return frameCount;
    }

    // The last frame may run past the audio.
    private void skipAudio(final BoundedInputStream audio, final long count) throws IOException {
        audio.skipAvailableBytes(count);
    }

    /**
     * Finds the next frame as FFmpeg's parser splits the audio into frames: the first four bytes from where the last
     * frame ended that make a valid header, of any layer, version or sample rate, but not free format, whose header
     * gives no length. FFmpeg's decoder skips zero bytes before a frame's header, but drops a frame after any other
     * byte, so the frame says whether there's one. Free format is only found in a free-format stream, which FFmpeg
     * can't decode.
     *
     * @param audio the audio from where the decoder started, which the bit stream reads
     * @param audioLength how far from there the audio ends, so where no frame starts
     * @param unpaddedLength how many bytes each frame of a free-format stream takes but for its padding, otherwise 0
     * @return the frame, whose header the bit stream holds, peeked, or {@code NoMoreFrames} once the audio ends
     */
    FrameSearch findFrame(
            final BoundedInputStream audio, final BitInputStream bits, final long audioLength, final int unpaddedLength)
            throws IOException {
        var lead = FrameLead.AFTER_ZEROS;
        try {
            while (true) {
                final var word = bits.peekBits(32);
                // The header is peeked, so the audio is past it, and a header the audio ends in is no frame: its last
                // bytes would be the zeros after the file.
                if (audio.getCount() > audioLength) {
                    return new NoMoreFrames();
                }
                if (headerOf(word) instanceof HeaderFound found
                        && (found.header().bitrate() == FrameHeader.FREE_FORMAT_BITRATE) == (unpaddedLength > 0)) {
                    return new FoundFrame(withLength(found.header(), unpaddedLength), lead);
                }
                if (word >>> 24 != 0) {
                    lead = FrameLead.AFTER_OTHER_BYTES;
                }
                bits.readBits(8);
            }
        } catch (final EOFException end) {
            return new NoMoreFrames();
        }
    }

    /**
     * What {@link #findFrame} found.
     */
    sealed interface FrameSearch permits FoundFrame, NoMoreFrames {}

    /**
     * A frame.
     *
     * @param lead what came before it, which makes FFmpeg's decoder drop it when it's bytes other than 0
     */
    record FoundFrame(FrameHeader header, FrameLead lead) implements FrameSearch {}

    /**
     * The audio ended without another frame.
     */
    record NoMoreFrames() implements FrameSearch {}

    /**
     * What came between the last frame and the next one.
     */
    enum FrameLead {
        AFTER_ZEROS,
        AFTER_OTHER_BYTES
    }

    /**
     * @param unpaddedLength how many bytes each frame of the stream takes but for its padding, which only a free-format
     *     frame's header needs given
     */
    FrameHeader withLength(final FrameHeader header, final int unpaddedLength) {
        if (header.bitrate() == FrameHeader.FREE_FORMAT_BITRATE) {
            return header.withFreeFormatLength(unpaddedLength);
        }
        return header;
    }

    /**
     * Reads a frame's header.
     */
    FrameHeader readAudioFrameHeader(final BitInputStream bits) throws IOException, InvalidFormatException {
        return frameHeader(bits.readBits(32));
    }

    // Bytes that aren't a frame rarely start with a frame sync, so the other fields are only read when they do.
    private HeaderAt headerOf(final long header) {
        if ((header & SYNC) != SYNC) {
            return new NoHeader();
        }
        try {
            return new HeaderFound(frameHeader(header));
        } catch (final InvalidFormatException reservedField) {
            return new NoHeader();
        }
    }

    // The header's 32 bits: the sync (11), version (2), layer (2), protection bit, bitrate index (4), sample rate index
    // (2), padding bit, the encoder's own private bit, channel mode (2) and mode extension (2), then the copyright,
    // original and emphasis fields. Only Layer III may be in free format, as FFmpeg finds no free-format frames at all.
    private FrameHeader frameHeader(final long header) throws InvalidFormatException {
        if ((header & SYNC) != SYNC) {
            throw new InvalidFormatException("MPEG audio frame doesn't start with a frame sync!");
        }
        final var version = MpegVersion.fromBits(field(header, 19, 2));
        final var layer = Layer.fromBits(field(header, 17, 2));
        final var protection = Protection.fromBit(field(header, 16, 1));
        final var bitrate = version.bitrate(layer, field(header, 12, 4));
        if (bitrate == FrameHeader.FREE_FORMAT_BITRATE && layer != Layer.III) {
            throw new InvalidFormatException("Free-format Layer I and II frames can't be decoded!");
        }
        final var sampleRate = version.sampleRate(field(header, 10, 2));
        final var padding = field(header, 9, 1);
        final var channelMode = ChannelMode.values()[field(header, 6, 2)];
        final var modeExtension = field(header, 4, 2);
        return new FrameHeader(version, layer, protection, bitrate, sampleRate, padding, channelMode, modeExtension);
    }

    private static int field(final long header, final int shift, final int size) {
        return (int) (header >>> shift) & ((1 << size) - 1);
    }

    /**
     * @param input the file's stream, at its start
     * @return the tag the file starts with, which is empty, with a size of 0, when it starts with none
     */
    Id3v2Tag readId3v2Tag(final InputStream input) throws IOException, InvalidFormatException {
        final var bits = BitInputStream.wrap(input, ByteOrder.BIG_ENDIAN);
        if (bits.peekBits(24) != IDENTIFIER) {
            return new Id3v2Tag(0, List.of());
        }
        bits.readBits(24);
        final var version = Id3Version.fromMajorVersion((int) bits.readBits(8));
        // The revision changes nothing a reader needs.
        bits.readBits(8);
        final var flags = (int) bits.readBits(8);
        final var bodySize = Id3Utils.readSynchsafeInt(bits);
        // The bit stream has read all of the header, so the body follows in the stream.
        try {
            final var body = new Id3Region(input, bodySize, version.bodySynchronisation(flags));
            return new Id3v2Tag(tagSize(flags, bodySize), readBody(version, flags, body));
        } catch (final EOFException cutShort) {
            throw new InvalidFormatException("ID3v2 tag is cut short!");
        }
    }

    private long tagSize(final int flags, final int bodySize) {
        if ((flags & FOOTER) != 0) {
            return HEADER_SIZE + bodySize + FOOTER_SIZE;
        }
        return HEADER_SIZE + bodySize;
    }

    // A compressed ID3v2.2 tag is skipped, as its spec says.
    private List<Id3Frame> readBody(final Id3Version version, final int tagFlags, final Id3Region body)
            throws IOException, InvalidFormatException {
        if (version.isCompressedTag(tagFlags)) {
            body.skip(Long.MAX_VALUE);
            return List.of();
        }
        return readFrames(version, tagFlags, body);
    }

    // Keeps the frames Sona reads, and skips the rest, as the spec lets a reader skip frames it doesn't know. Padding,
    // which must be all NULs, may follow the last frame, and no frame ID starts with a NUL.
    private List<Id3Frame> readFrames(final Id3Version version, final int tagFlags, final Id3Region body)
            throws IOException, InvalidFormatException {
        final var bits = BitInputStream.wrap(body, ByteOrder.BIG_ENDIAN);
        skipExtendedHeader(version, tagFlags, body, bits);
        final var frames = new ArrayList<Id3Frame>();
        while (body.hasMore() && body.peek() != 0) {
            final var header = readFrameHeader(version, bits);
            if (isReadFrameId(header.frameId()) && !version.isEncrypted(header.formatFlags())) {
                frames.add(readFrame(version, tagFlags, header, body));
            } else {
                body.skipNBytes(header.size());
            }
        }
        readPadding(body, bits);
        return List.copyOf(frames);
    }

    private void skipExtendedHeader(
            final Id3Version version, final int tagFlags, final Id3Region body, final BitInputStream bits)
            throws IOException, InvalidFormatException {
        final var size = version.readExtendedHeaderSize(bits, tagFlags);
        if (size < 0) {
            throw new InvalidFormatException("ID3v2 extended header is smaller than its size field!");
        }
        body.skipNBytes(size);
    }

    private void readPadding(final Id3Region body, final BitInputStream bits)
            throws IOException, InvalidFormatException {
        while (body.hasMore()) {
            if (bits.readBits(8) != 0) {
                throw new InvalidFormatException("ID3v2 tag's padding isn't all NULs!");
            }
        }
    }

    private Id3FrameHeader readFrameHeader(final Id3Version version, final BitInputStream bits)
            throws IOException, InvalidFormatException {
        final var header = version.readFrameHeader(bits);
        if (header.size() == 0) {
            throw new InvalidFormatException("ID3v2 frame %s is empty!".formatted(header.frameId()));
        }
        return header;
    }

    private boolean isReadFrameId(final String frameId) {
        return frameId.equals("UFID") || frameId.startsWith("T");
    }

    // The data follows the fields the frame's flags add. Compressed data may end before the frame does, so the rest
    // is skipped.
    private Id3Frame readFrame(
            final Id3Version version, final int tagFlags, final Id3FrameHeader header, final Id3Region body)
            throws IOException, InvalidFormatException {
        final var synchronisation = version.frameSynchronisation(tagFlags, header.formatFlags());
        final var frame = new Id3Region(body, header.size(), synchronisation);
        final var inflatedSize =
                version.readFrameFields(BitInputStream.wrap(frame, ByteOrder.BIG_ENDIAN), header.formatFlags());
        final var id3Frame = readFrameData(version, header, frame, inflatedSize);
        frame.skip(Long.MAX_VALUE);
        return id3Frame;
    }

    private Id3Frame readFrameData(
            final Id3Version version, final Id3FrameHeader header, final Id3Region frame, final long inflatedSize)
            throws IOException, InvalidFormatException {
        if (!version.isCompressed(header.formatFlags())) {
            return readFrameContent(header.frameId(), frame);
        }
        if (inflatedSize == 0) {
            throw new InvalidFormatException(
                    "ID3v2 frame %s is compressed without its size!".formatted(header.frameId()));
        }
        return readCompressedFrame(header.frameId(), frame, inflatedSize);
    }

    // Compressed data is zlib's, and the content is read as it's inflated.
    private Id3Frame readCompressedFrame(final String frameId, final Id3Region frame, final long inflatedSize)
            throws IOException, InvalidFormatException {
        try (final var inflated = new InflaterInputStream(frame)) {
            return readFrameContent(frameId, new Id3Region(inflated, inflatedSize, Synchronisation.SYNCHRONISED));
        } catch (final ZipException notZlib) {
            throw new InvalidFormatException("ID3v2 frame %s isn't valid zlib data!".formatted(frameId));
        }
    }

    private Id3Frame readFrameContent(final String frameId, final Id3Region content)
            throws IOException, InvalidFormatException {
        if (!content.hasMore()) {
            throw new InvalidFormatException("ID3v2 frame %s has no content!".formatted(frameId));
        }
        final var bits = BitInputStream.wrap(content, ByteOrder.BIG_ENDIAN);
        return switch (frameId) {
            case "TXXX" -> readUserText(content, bits);
            case "UFID" -> readUniqueFileId(content, bits);
            default -> new TextFrame(frameId, readEncoding(bits).readStrings(content, bits));
        };
    }

    // The first string names the text, and the rest are its values.
    private UserTextFrame readUserText(final Id3Region frame, final BitInputStream bits)
            throws IOException, InvalidFormatException {
        final var strings = readEncoding(bits).readStrings(frame, bits);
        if (strings.isEmpty()) {
            throw new InvalidFormatException("ID3v2 TXXX frame has no description!");
        }
        return new UserTextFrame(strings.getFirst(), strings.subList(1, strings.size()));
    }

    // The owner is NUL-terminated ISO-8859-1. The identifier is binary, but MusicBrainz writes ASCII.
    private UniqueFileId readUniqueFileId(final Id3Region frame, final BitInputStream bits)
            throws IOException, InvalidFormatException {
        final var strings = TextEncoding.ISO_8859_1.readStrings(frame, bits);
        if (strings.size() != 2) {
            throw new InvalidFormatException("ID3v2 UFID frame isn't an owner followed by an identifier!");
        }
        return new UniqueFileId(strings.getFirst(), strings.get(1));
    }

    // A text frame's first byte says how it encodes its text.
    private TextEncoding readEncoding(final BitInputStream bits) throws IOException, InvalidFormatException {
        return TextEncoding.fromByte((int) bits.readBits(8));
    }

    // ID3v1's fields are cut-down copies of ID3v2's where a file has both, so they only fill in what ID3v2 lacks.
    // An appended tag updates the tag before the audio. A tag may hold several frames of one kind, so a tag's frames
    // only replace another's whole kinds.
    private List<Id3Frame> mergeFrames(final List<List<? extends Id3Frame>> tagsByPrecedence) {
        final var frames = new ArrayList<Id3Frame>();
        final var mergedKeys = new HashSet<String>();
        for (final var tag : tagsByPrecedence) {
            final var tagKeys = tag.stream().map(this::frameKey).collect(Collectors.toUnmodifiableSet());
            tag.stream().filter(frame -> !mergedKeys.contains(frameKey(frame))).forEach(frames::add);
            mergedKeys.addAll(tagKeys);
        }
        return List.copyOf(frames);
    }

    // What a frame holds: a text frame's ID, a TXXX frame's description, or a UFID frame's owner.
    private String frameKey(final Id3Frame frame) {
        return switch (frame) {
            case TextFrame text -> text.frameId();
            case UserTextFrame userText -> "TXXX:" + userText.description();
            case UniqueFileId uniqueFileId -> "UFID:" + uniqueFileId.owner();
        };
    }

    private RawMetadata rawMetadata(final Duration duration, final List<Id3Frame> frames) {
        final var rawMetadata = new RawMetadataBuilder(duration);
        for (final var frame : frames) {
            final var id3Tag = id3Tag(frame);
            final var values = frameValues(frame);
            for (final var value : values) {
                if (id3Tag != null) {
                    rawMetadata.addTag(id3Tag.getTag(), id3Tag.toTagValue(value));
                } else {
                    rawMetadata.addUnknownTag(unknownTagName(frame), value);
                }
            }
        }
        return rawMetadata.build();
    }

    // Null for a frame that holds none of Sona's tags.
    private Id3Tag id3Tag(final Id3Frame frame) {
        return switch (frame) {
            case TextFrame text -> Id3Tag.fromFrameId(text.frameId());
            case UserTextFrame _, UniqueFileId _ -> null;
        };
    }

    private List<String> frameValues(final Id3Frame frame) {
        return switch (frame) {
            case TextFrame text -> text.values();
            case UserTextFrame userText -> userText.values();
            case UniqueFileId uniqueFileId -> List.of(uniqueFileId.identifier());
        };
    }

    private String unknownTagName(final Id3Frame frame) {
        return switch (frame) {
            case TextFrame text -> text.frameId().toLowerCase(Locale.ROOT);
            case UserTextFrame userText ->
                PICARD_USER_TEXT.getOrDefault(
                        userText.description(), userText.description().toLowerCase(Locale.ROOT));
            case UniqueFileId uniqueFileId -> uniqueFileIdName(uniqueFileId.owner());
        };
    }

    private String uniqueFileIdName(final String owner) {
        if (owner.equals(MUSICBRAINZ_OWNER)) {
            return MUSICBRAINZ_RECORDING_ID;
        }
        return "ufid:" + owner.toLowerCase(Locale.ROOT);
    }
}
