package dev.javax.sonaudio.format.opus;

import dev.javax.bitstream.BitInputStream;
import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.Format;
import dev.javax.sonaudio.format.Parser;
import dev.javax.sonaudio.format.opus.NextPage.Found;
import dev.javax.sonaudio.format.opus.NextPage.NoMorePages;
import dev.javax.sonaudio.format.opus.PageGranule.Positioned;
import dev.javax.sonaudio.format.opus.PageGranule.Unpositioned;
import dev.javax.sonaudio.internal.Crc;
import dev.javax.sonaudio.internal.VorbisCommentReader;
import dev.javax.sonaudio.metadata.RawMetadata;
import dev.javax.sonaudio.metadata.RawMetadataBuilder;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads the metadata of an Ogg file's Opus stream (RFC 7845), and hands out an {@link OpusDecoder} of its audio. A
 * malformed file throws {@link dev.javax.sonaudio.exception.InvalidFormatException}.
 *
 * <p>The stream is the first logical stream whose first page starts with {@code OpusHead}, as the stream FFmpeg's
 * command line picks of a multiplexed file; the pages of others are skipped. The pages are read strictly where FFmpeg
 * is forgiving: a page whose CRC-32, version or capture pattern is wrong, one that is cut short, or a gap in the
 * pages' data is an {@link InvalidFormatException} where FFmpeg looks for the next page. A chained file, whose stream
 * ends and another starts, is one too. The file's length is the granule position of the last page in its last 65307
 * bytes, where the end of a file may start inside a page, so there the search does go on past what isn't a page, as
 * FFmpeg's does.
 *
 * <p>A packet's framing (RFC 6716 section 3) is read here too, for the decoder.
 *
 * <p>The page header and OpusHead are read through the bit reader. The pages themselves are read as bytes, as their
 * CRC covers all of them and a packet may continue across pages, and a packet is a byte array for the range decoder,
 * which reads raw bits from the end of a frame backwards.
 */
public final class OpusParser implements Parser {

    // "OggS", as the little-endian bit reader reads its four bytes.
    private static final int CAPTURE_PATTERN = 0x53_67_67_4F;
    private static final byte[] CAPTURE_BYTES = {'O', 'g', 'g', 'S'};
    private static final byte[] HEAD_MAGIC = {'O', 'p', 'u', 's', 'H', 'e', 'a', 'd'};
    private static final byte[] TAGS_MAGIC = {'O', 'p', 'u', 's', 'T', 'a', 'g', 's'};
    private static final int MAGIC_SIZE = 8;
    private static final int PAGE_HEADER_SIZE = 27;
    private static final int CRC_OFFSET = 22;
    private static final int CRC_SIZE = 4;
    // The largest an Ogg page can be: 27 header bytes, 255 segment lengths and 255 segments of 255 bytes.
    private static final int MAX_PAGE_SIZE = 27 + 255 + 255 * 255;
    private static final int CONTINUED_FLAG = 0x01;
    private static final int BEGINNING_FLAG = 0x02;
    private static final int END_FLAG = 0x04;
    private static final int FULL_SEGMENT = 255;
    private static final long OGG_CRC_POLYNOMIAL = 0x04C11DB7L;
    private static final int CRC_WIDTH = 32;
    private static final int OPUS_HEAD_SIZE = 19;
    private static final int MAJOR_VERSION_MASK = 0xF0;
    private static final int UNMAPPED_CHANNEL = 255;
    private static final int MAX_VORBIS_ORDER_CHANNELS = 8;
    private static final int MAX_AMBISONIC_CHANNELS = 227;
    // RFC 6716 sections 3.1 and 3.2: a frame has at most 1275 bytes, a packet 48 frames and 120 ms of audio.
    private static final int MAX_FRAME_BYTES = 1275;
    private static final int MAX_FRAMES = 48;
    private static final int MAX_PACKET_SAMPLES = 5760;
    private static final int LONG_LENGTH_FROM = 252;
    // The samples at 48 kHz of a frame of each TOC configuration (RFC 6716 section 3.1, table 2).
    private static final int[] CONFIG_FRAME_SAMPLES = {
        480, 960, 1920, 2880, 480, 960, 1920, 2880, 480, 960, 1920, 2880, 480, 960, 480, 960, 120, 240, 480, 960, 120,
        240, 480, 960, 120, 240, 480, 960, 120, 240, 480, 960
    };
    private static final int FIRST_HYBRID_CONFIG = 12;
    private static final int FIRST_CELT_CONFIG = 16;
    private static final int FIRST_FULLBAND_HYBRID_CONFIG = 14;

    private final VorbisCommentReader vorbisCommentReader = new VorbisCommentReader();

    /**
     * Makes a parser, which keeps no state between files, so one can parse every Opus file, from any thread.
     */
    public OpusParser() {}

    @Override
    public Format format() {
        return Format.OPUS;
    }

    @Override
    public ParsedOpus parse(final SeekableByteChannel channel) throws IOException, InvalidFormatException {
        channel.position(0);
        final var firstPage = readHeadPage(new BufferedInputStream(Channels.newInputStream(channel)));
        final var head = readOpusHead(firstPage.fragments().getFirst().bytes());
        final var lastGranule = readLastGranule(channel, firstPage.serial());
        final var info = head.withSampleCount(sampleCount(lastGranule, head.preSkip()));
        channel.position(0);
        final var input = new BufferedInputStream(Channels.newInputStream(channel));
        final var stream = new OggStream(this, input, firstPage.serial(), info.preSkip());
        stream.nextHeaderPacket();
        final var metadata = readOpusTags(stream.nextHeaderPacket(), info);
        stream.requireAudioOnFreshPage();
        return new ParsedOpus(metadata, new OpusDecoder(this, info, stream));
    }

    // FFmpeg's duration is the last granule position, which counts the pre-skip that the decoder drops.
    private long sampleCount(final PageGranule lastGranule, final int preSkip) {
        return switch (lastGranule) {
            case Unpositioned _ -> 0;
            case Positioned(final var samples) -> Math.max(0, samples - preSkip);
        };
    }

    private RawMetadata readOpusTags(final byte[] packet, final OpusStreamInfo info)
            throws IOException, InvalidFormatException {
        if (!startsWith(packet, TAGS_MAGIC)) {
            throw new InvalidFormatException("Opus stream's second packet isn't OpusTags");
        }
        final var size = packet.length - MAGIC_SIZE;
        final var builder = new RawMetadataBuilder(info.duration());
        try {
            final var comments =
                    vorbisCommentReader.readVorbisComments(new ByteArrayInputStream(packet, MAGIC_SIZE, size), size);
            final var fields = comments.fields();
            for (final var field : fields) {
                field.addTag(builder);
            }
        } catch (final EOFException cutShort) {
            throw new InvalidFormatException("OpusTags is cut short!");
        }
        return builder.build();
    }

    private boolean startsWith(final byte[] packet, final byte[] magic) {
        return packet.length >= magic.length && Arrays.equals(packet, 0, magic.length, magic, 0, magic.length);
    }

    /**
     * Reads the page that starts the Opus stream: the first page of the file that begins a stream and holds an
     * {@code OpusHead}. Pages of other streams before it are skipped.
     */
    private OggPage readHeadPage(final InputStream input) throws IOException, InvalidFormatException {
        while (true) {
            switch (readPage(input)) {
                case NoMorePages _ -> throw new InvalidFormatException("No Opus stream in the Ogg file");
                case Found(final var page) -> {
                    if (page.start() == PageStart.FOLLOWING) {
                        throw new InvalidFormatException("No Opus stream in the Ogg file");
                    }
                    if (holdsOpusHead(page)) {
                        return page;
                    }
                }
            }
        }
    }

    private boolean holdsOpusHead(final OggPage page) {
        final var fragments = page.fragments();
        return !fragments.isEmpty() && startsWith(fragments.getFirst().bytes(), HEAD_MAGIC);
    }

    /**
     * Reads one Ogg page (RFC 3533 section 6) from the stream, checking its capture pattern, version and CRC-32. The
     * stream may end only between pages.
     *
     * @param input the stream, at the start of a page
     */
    NextPage readPage(final InputStream input) throws IOException, InvalidFormatException {
        final var headerBytes = input.readNBytes(PAGE_HEADER_SIZE);
        if (headerBytes.length == 0) {
            return new NoMorePages();
        }
        if (headerBytes.length < PAGE_HEADER_SIZE) {
            throw new InvalidFormatException("Ogg page is cut short!");
        }
        final var header = readPageHeader(headerBytes);
        final var lacing = readExactly(input, header.segmentCount());
        final var body = readExactly(input, bodySize(lacing));
        verifyCrc(headerBytes, lacing, body, header.crc());
        return new Found(new OggPage(
                continuation(header.flags()),
                start(header.flags()),
                end(header.flags()),
                granule(header.granule()),
                header.serial(),
                header.sequence(),
                List.copyOf(splitFragments(lacing, body))));
    }

    private PageHeader readPageHeader(final byte[] headerBytes) throws IOException, InvalidFormatException {
        final var fields = BitInputStream.wrap(new ByteArrayInputStream(headerBytes), ByteOrder.LITTLE_ENDIAN);
        if (fields.readBits(Integer.SIZE) != CAPTURE_PATTERN) {
            throw new InvalidFormatException("Not an Ogg page: no OggS capture pattern");
        }
        final var version = (int) fields.readBits(Byte.SIZE);
        if (version != 0) {
            throw new InvalidFormatException("Unsupported Ogg version: " + version);
        }
        final var flags = (int) fields.readBits(Byte.SIZE);
        final var granuleLow = fields.readBits(Integer.SIZE);
        final var granule = fields.readBits(Integer.SIZE) << Integer.SIZE | granuleLow;
        final var serial = (int) fields.readBits(Integer.SIZE);
        final var sequence = (int) fields.readBits(Integer.SIZE);
        final var crc = fields.readBits(Integer.SIZE);
        return new PageHeader(flags, granule, serial, sequence, crc, (int) fields.readBits(Byte.SIZE));
    }

    private byte[] readExactly(final InputStream input, final int length) throws IOException, InvalidFormatException {
        final var bytes = input.readNBytes(length);
        if (bytes.length < length) {
            throw new InvalidFormatException("Ogg page is cut short!");
        }
        return bytes;
    }

    private int bodySize(final byte[] lacing) {
        var size = 0;
        for (final var segment : lacing) {
            size += segment & 0xFF;
        }
        return size;
    }

    // The CRC covers the whole page with its own field read as zeros, so the page is read as bytes, whose fields the
    // bit
    // reader then parses.
    private void verifyCrc(final byte[] headerBytes, final byte[] lacing, final byte[] body, final long expected)
            throws InvalidFormatException {
        final var crc = new Crc(OGG_CRC_POLYNOMIAL, CRC_WIDTH);
        crc.update(headerBytes, 0, CRC_OFFSET);
        crc.update(new byte[CRC_SIZE], 0, CRC_SIZE);
        crc.update(headerBytes, CRC_OFFSET + CRC_SIZE, PAGE_HEADER_SIZE - CRC_OFFSET - CRC_SIZE);
        crc.update(lacing, 0, lacing.length);
        crc.update(body, 0, body.length);
        if (crc.getValue() != expected) {
            throw new InvalidFormatException("Ogg page's CRC-32 is wrong");
        }
    }

    private PageContinuation continuation(final int flags) {
        if ((flags & CONTINUED_FLAG) != 0) {
            return PageContinuation.CONTINUED;
        }
        return PageContinuation.FRESH;
    }

    private PageStart start(final int flags) {
        if ((flags & BEGINNING_FLAG) != 0) {
            return PageStart.BEGINNING;
        }
        return PageStart.FOLLOWING;
    }

    private PageEnd end(final int flags) {
        if ((flags & END_FLAG) != 0) {
            return PageEnd.LAST;
        }
        return PageEnd.MORE;
    }

    private PageGranule granule(final long granule) {
        if (granule == -1L) {
            return new Unpositioned();
        }
        return new Positioned(granule);
    }

    // A segment of fewer than 255 bytes ends a packet; a page may end inside one, which the next page goes on with.
    private List<PacketFragment> splitFragments(final byte[] lacing, final byte[] body) {
        final var fragments = new ArrayList<PacketFragment>();
        var fragmentStart = 0;
        var position = 0;
        for (final var segment : lacing) {
            position += segment & 0xFF;
            if ((segment & 0xFF) < FULL_SEGMENT) {
                fragments.add(new PacketFragment(
                        Arrays.copyOfRange(body, fragmentStart, position), FragmentEnd.COMPLETES_PACKET));
                fragmentStart = position;
            }
        }
        if (fragmentStart < position) {
            fragments.add(new PacketFragment(
                    Arrays.copyOfRange(body, fragmentStart, position), FragmentEnd.CONTINUES_PACKET));
        }
        return fragments;
    }

    private PageGranule readLastGranule(final SeekableByteChannel channel, final int serial)
            throws IOException, InvalidFormatException {
        final var tail = readTail(channel);
        var last = (PageGranule) new Unpositioned();
        for (var index = nextCapturePattern(tail, 0); index >= 0; index = nextCapturePattern(tail, index + 1)) {
            if (pageAt(tail, index) instanceof Found(final var page)
                    && page.serial() == serial
                    && page.granule() instanceof Positioned(final var samples)
                    && samples != 0) {
                last = page.granule();
            }
        }
        return last;
    }

    private byte[] readTail(final SeekableByteChannel channel) throws IOException {
        final var size = channel.size();
        final var start = Math.max(0, size - MAX_PAGE_SIZE);
        channel.position(start);
        final var tail = ByteBuffer.allocate((int) (size - start));
        while (tail.hasRemaining() && channel.read(tail) >= 0) {
            // Keeps reading until the tail is full or the file ends.
        }
        return tail.array();
    }

    private int nextCapturePattern(final byte[] bytes, final int from) {
        for (var index = from; index + CAPTURE_BYTES.length <= bytes.length; index++) {
            if (Arrays.equals(bytes, index, index + CAPTURE_BYTES.length, CAPTURE_BYTES, 0, CAPTURE_BYTES.length)) {
                return index;
            }
        }
        return -1;
    }

    // Anything that isn't a whole page with a good CRC is a place the capture pattern happened to be, not a page.
    private NextPage pageAt(final byte[] bytes, final int index) throws IOException {
        try {
            return readPage(new ByteArrayInputStream(bytes, index, bytes.length - index));
        } catch (final InvalidFormatException notAPage) {
            return new NoMorePages();
        }
    }

    private OpusStreamInfo readOpusHead(final byte[] packet) throws IOException, InvalidFormatException {
        if (packet.length < OPUS_HEAD_SIZE) {
            throw new InvalidFormatException("OpusHead is " + packet.length + " bytes, not " + OPUS_HEAD_SIZE);
        }
        final var fields = BitInputStream.wrap(
                new ByteArrayInputStream(packet, MAGIC_SIZE, packet.length - MAGIC_SIZE), ByteOrder.LITTLE_ENDIAN);
        final var version = (int) fields.readBits(Byte.SIZE);
        if ((version & MAJOR_VERSION_MASK) != 0) {
            throw new InvalidFormatException("Unsupported OpusHead version: " + version);
        }
        final var channelCount = (int) fields.readBits(Byte.SIZE);
        if (channelCount == 0) {
            throw new InvalidFormatException("OpusHead has no channels");
        }
        final var preSkip = (int) fields.readBits(Short.SIZE);
        final var inputSampleRate = (int) fields.readBits(Integer.SIZE);
        final var outputGain = (short) fields.readBits(Short.SIZE);
        final var family = (int) fields.readBits(Byte.SIZE);
        final var head = new OpusStreamInfo(
                version,
                channelCount,
                preSkip,
                inputSampleRate,
                outputGain,
                mappingFamily(family),
                1,
                channelCount - 1,
                new int[] {0, 1},
                0);
        try {
            return readChannelMapping(fields, head);
        } catch (final EOFException cutShort) {
            throw new InvalidFormatException("OpusHead's channel mapping table is cut short!");
        }
    }

    private ChannelMappingFamily mappingFamily(final int family) throws InvalidFormatException {
        return switch (family) {
            case 0 -> ChannelMappingFamily.SINGLE_STREAM;
            case 1 -> ChannelMappingFamily.VORBIS_ORDER;
            case 2 -> ChannelMappingFamily.AMBISONICS;
            case 255 -> ChannelMappingFamily.UNDEFINED;
            default -> throw new InvalidFormatException("Unsupported Opus channel mapping family: " + family);
        };
    }

    // Family 0 has no table in its header, so the info already holds the one it implies.
    private OpusStreamInfo readChannelMapping(final BitInputStream fields, final OpusStreamInfo head)
            throws IOException, InvalidFormatException {
        if (head.mappingFamily() == ChannelMappingFamily.SINGLE_STREAM) {
            if (head.channelCount() > 2) {
                throw new InvalidFormatException("Channel mapping family 0 is for up to 2 channels");
            }
            return head;
        }
        final var channelCount = head.channelCount();
        final var streams = (int) fields.readBits(Byte.SIZE);
        final var coupled = (int) fields.readBits(Byte.SIZE);
        final var mapping = new int[channelCount];
        for (var channel = 0; channel < channelCount; channel++) {
            mapping[channel] = (int) fields.readBits(Byte.SIZE);
        }
        final var info = new OpusStreamInfo(
                head.version(),
                channelCount,
                head.preSkip(),
                head.inputSampleRate(),
                head.outputGain(),
                head.mappingFamily(),
                streams,
                coupled,
                mapping,
                0);
        validateMapping(info);
        return info;
    }

    private void validateMapping(final OpusStreamInfo info) throws InvalidFormatException {
        final var streams = info.streamCount();
        final var coupled = info.coupledStreamCount();
        if (streams == 0 || coupled > streams || streams + coupled > 255) {
            throw new InvalidFormatException("Invalid Opus stream counts: " + streams + " and " + coupled + " coupled");
        }
        validateChannelCount(info);
        final var mapping = info.channelMapping();
        for (final var source : mapping) {
            if (source != UNMAPPED_CHANNEL && source >= streams + coupled) {
                throw new InvalidFormatException("Opus channel mapping to a stream channel that doesn't exist");
            }
        }
    }

    private void validateChannelCount(final OpusStreamInfo info) throws InvalidFormatException {
        final var channelCount = info.channelCount();
        switch (info.mappingFamily()) {
            case VORBIS_ORDER -> {
                if (channelCount > MAX_VORBIS_ORDER_CHANNELS) {
                    throw new InvalidFormatException("Channel mapping family 1 is for up to 8 channels");
                }
            }
            case AMBISONICS -> validateAmbisonicChannels(channelCount);
            case SINGLE_STREAM, UNDEFINED -> {}
        }
    }

    // (n + 1) squared channels for ambisonics of order n, and two more for a stereo pair beside them.
    private void validateAmbisonicChannels(final int channelCount) throws InvalidFormatException {
        final var orderPlusOne = (int) Math.sqrt(channelCount);
        final var ambisonic = orderPlusOne * orderPlusOne;
        if ((channelCount != ambisonic && channelCount != ambisonic + 2) || channelCount > MAX_AMBISONIC_CHANNELS) {
            throw new InvalidFormatException("Invalid channel count for Opus ambisonics: " + channelCount);
        }
    }

    /**
     * Reads the framing of one stream's part of an Opus packet (RFC 6716 section 3), rejecting what FFmpeg's
     * {@code ff_opus_parse_packet} does: RFC 6716 section 3.4's requirements for frame sizes, counts and padding,
     * and a packet whose frames would last more than 120 ms.
     *
     * @param data the bytes of the packet, which may have other streams' parts after this one
     * @param offset where this stream's part starts
     * @param length how many bytes there are from there
     * @param delimiting whether the part says where it ends
     * @return the part's framing
     * @throws InvalidFormatException when the packet is malformed
     */
    // The packet is bytes, as the Ogg layer put its fragments together, which the range decoder reads from both ends.
    OpusPacket parsePacket(final byte[] data, final int offset, final int length, final Delimiting delimiting)
            throws InvalidFormatException {
        if (length < 1) {
            throw new InvalidFormatException("Opus packet is empty");
        }
        final var input = new ByteArrayInputStream(data, offset, length);
        final var toc = input.read();
        final var code = toc & 3;
        if (code >= 2 && length < 2) {
            throw new InvalidFormatException("Opus packet of code " + code + " is only a TOC byte");
        }
        final var layout = switch (code) {
            case 0 -> singleFrame(input, length, delimiting);
            case 1 -> twoEqualFrames(input, length, delimiting);
            case 2 -> twoDifferentFrames(input, length, delimiting);
            default -> arbitraryFrames(input, length, delimiting);
        };
        return framedPacket(toc >> 3, (toc >> 2) & 1, layout);
    }

    private OpusPacket framedPacket(final int config, final int stereoFlag, final FrameLayout layout)
            throws InvalidFormatException {
        final var frameSamples = CONFIG_FRAME_SAMPLES[config];
        if ((long) frameSamples * layout.sizes().length > MAX_PACKET_SAMPLES) {
            throw new InvalidFormatException("Opus packet lasts more than 120 ms");
        }
        var coding = ChannelCoding.MONO;
        if (stereoFlag == 1) {
            coding = ChannelCoding.STEREO;
        }
        return new OpusPacket(
                mode(config), bandwidth(config), coding, frameSamples, layout.offsets(), layout.sizes(), layout.end());
    }

    private OpusMode mode(final int config) {
        if (config < FIRST_HYBRID_CONFIG) {
            return OpusMode.SILK;
        }
        if (config < FIRST_CELT_CONFIG) {
            return OpusMode.HYBRID;
        }
        return OpusMode.CELT;
    }

    private OpusBandwidth bandwidth(final int config) {
        final var bandwidths = OpusBandwidth.values();
        if (config < FIRST_HYBRID_CONFIG) {
            return bandwidths[config >> 2];
        }
        if (config < FIRST_CELT_CONFIG) {
            if (config >= FIRST_FULLBAND_HYBRID_CONFIG) {
                return OpusBandwidth.FULLBAND;
            }
            return OpusBandwidth.SUPERWIDEBAND;
        }
        // CELT has no medium band, so its bandwidths skip it.
        final var celtBandwidth = (config - FIRST_CELT_CONFIG) >> 2;
        if (celtBandwidth == 0) {
            return OpusBandwidth.NARROWBAND;
        }
        return bandwidths[celtBandwidth + 1];
    }

    private FrameLayout singleFrame(final ByteArrayInputStream input, final int length, final Delimiting delimiting)
            throws InvalidFormatException {
        var end = length;
        if (delimiting == Delimiting.SELF_DELIMITED) {
            final var delimitedLength = readShortLength(input);
            requireAvailable(input, delimitedLength);
            end = position(input, length) + delimitedLength;
        }
        final var start = position(input, length);
        requireFrameSize(end - start);
        return new FrameLayout(new int[] {start}, new int[] {end - start}, end);
    }

    private FrameLayout twoEqualFrames(final ByteArrayInputStream input, final int length, final Delimiting delimiting)
            throws InvalidFormatException {
        var end = length;
        if (delimiting == Delimiting.SELF_DELIMITED) {
            final var delimitedLength = readShortLength(input);
            requireAvailable(input, 2 * delimitedLength);
            end = position(input, length) + 2 * delimitedLength;
        }
        final var start = position(input, length);
        final var bytes = end - start;
        if ((bytes & 1) != 0) {
            throw new InvalidFormatException("Opus packet of code 1 has frames of different sizes");
        }
        requireFrameSize(bytes / 2);
        return new FrameLayout(new int[] {start, start + bytes / 2}, new int[] {bytes / 2, bytes / 2}, end);
    }

    private FrameLayout twoDifferentFrames(
            final ByteArrayInputStream input, final int length, final Delimiting delimiting)
            throws InvalidFormatException {
        final var firstSize = readShortLength(input);
        var end = length;
        if (delimiting == Delimiting.SELF_DELIMITED) {
            final var delimitedLength = readShortLength(input);
            requireAvailable(input, firstSize + delimitedLength);
            end = position(input, length) + firstSize + delimitedLength;
        }
        final var start = position(input, length);
        final var secondSize = end - start - firstSize;
        if (secondSize < 0) {
            throw new InvalidFormatException("Opus packet's first frame is larger than the packet");
        }
        requireFrameSize(secondSize);
        return new FrameLayout(new int[] {start, start + firstSize}, new int[] {firstSize, secondSize}, end);
    }

    private FrameLayout arbitraryFrames(final ByteArrayInputStream input, final int length, final Delimiting delimiting)
            throws InvalidFormatException {
        final var countByte = input.read();
        final var frameCount = countByte & 0x3F;
        if (frameCount == 0 || frameCount > MAX_FRAMES) {
            throw new InvalidFormatException("Opus packet has " + frameCount + " frames");
        }
        var padding = 0;
        if ((countByte & 0x40) != 0) {
            padding = readLongLength(input);
        }
        if ((countByte & 0x80) != 0) {
            return variableFrames(input, length, delimiting, frameCount, padding);
        }
        return constantFrames(input, length, delimiting, frameCount, padding);
    }

    private FrameLayout variableFrames(
            final ByteArrayInputStream input,
            final int length,
            final Delimiting delimiting,
            final int frameCount,
            final int padding)
            throws InvalidFormatException {
        final var sizes = new int[frameCount];
        var codedBytes = 0;
        for (var frame = 0; frame < frameCount - 1; frame++) {
            sizes[frame] = readShortLength(input);
            codedBytes += sizes[frame];
        }
        var end = length;
        if (delimiting == Delimiting.SELF_DELIMITED) {
            final var delimitedLength = readShortLength(input);
            requireAvailable(input, delimitedLength + codedBytes + padding);
            end = position(input, length) + codedBytes + delimitedLength + padding;
        }
        final var start = position(input, length);
        final var frameBytes = end - start - padding;
        if (codedBytes > frameBytes) {
            throw new InvalidFormatException("Opus packet's frames are larger than the packet");
        }
        sizes[frameCount - 1] = frameBytes - codedBytes;
        return new FrameLayout(consecutiveOffsets(start, sizes), sizes, end);
    }

    private FrameLayout constantFrames(
            final ByteArrayInputStream input,
            final int length,
            final Delimiting delimiting,
            final int frameCount,
            final int padding)
            throws InvalidFormatException {
        var end = length;
        var frameBytes = 0;
        if (delimiting == Delimiting.SELF_DELIMITED) {
            frameBytes = readShortLength(input);
            requireAvailable(input, frameCount * frameBytes + padding);
            end = position(input, length) + frameCount * frameBytes + padding;
        } else {
            final var start = position(input, length);
            final var totalBytes = end - start - padding;
            if (totalBytes < 0 || totalBytes % frameCount != 0 || totalBytes / frameCount > MAX_FRAME_BYTES) {
                throw new InvalidFormatException("Opus packet's frames aren't all the same size");
            }
            frameBytes = totalBytes / frameCount;
        }
        final var sizes = new int[frameCount];
        Arrays.fill(sizes, frameBytes);
        return new FrameLayout(consecutiveOffsets(position(input, length), sizes), sizes, end);
    }

    private int[] consecutiveOffsets(final int start, final int[] sizes) {
        final var offsets = new int[sizes.length];
        var offset = start;
        for (var frame = 0; frame < sizes.length; frame++) {
            offsets[frame] = offset;
            offset += sizes[frame];
        }
        return offsets;
    }

    private int position(final ByteArrayInputStream input, final int length) {
        return length - input.available();
    }

    private void requireAvailable(final ByteArrayInputStream input, final int bytes) throws InvalidFormatException {
        if (bytes > input.available()) {
            throw new InvalidFormatException("Opus packet's lengths go past its end");
        }
    }

    private void requireFrameSize(final int bytes) throws InvalidFormatException {
        if (bytes > MAX_FRAME_BYTES) {
            throw new InvalidFormatException("Opus frame of " + bytes + " bytes is over " + MAX_FRAME_BYTES);
        }
    }

    // A frame's length is one byte, or for 252 and over a second, worth four each (RFC 6716 section 3.2.1).
    private int readShortLength(final ByteArrayInputStream input) throws InvalidFormatException {
        var length = readLengthByte(input);
        if (length >= LONG_LENGTH_FROM) {
            length += 4 * readLengthByte(input);
        }
        return length;
    }

    // Padding's length is bytes of 255, which add 254 and go on, then one of less, which adds itself (RFC 6716
    // section 3.2.5).
    private int readLongLength(final ByteArrayInputStream input) throws InvalidFormatException {
        var length = 0;
        while (true) {
            final var next = readLengthByte(input);
            if (length > Integer.MAX_VALUE - 254) {
                throw new InvalidFormatException("Opus packet's padding is too long");
            }
            length += next;
            if (next < 255) {
                return length;
            }
            length--;
        }
    }

    private int readLengthByte(final ByteArrayInputStream input) throws InvalidFormatException {
        final var next = input.read();
        if (next < 0) {
            throw new InvalidFormatException("Opus packet ends inside a length");
        }
        return next;
    }

    private record PageHeader(int flags, long granule, int serial, int sequence, long crc, int segmentCount) {}

    private record FrameLayout(int[] offsets, int[] sizes, int end) {}
}
