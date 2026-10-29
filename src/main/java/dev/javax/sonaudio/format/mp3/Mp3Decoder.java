package dev.javax.sonaudio.format.mp3;

import dev.javax.bitstream.BitInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.BoundedInputStream;
import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.exception.UncheckedInvalidFormatException;
import dev.javax.sonaudio.format.FloatPcmAudio;
import dev.javax.sonaudio.format.FloatPcmFrame;
import dev.javax.sonaudio.format.FrameContinuity;
import dev.javax.sonaudio.format.Iteration;

/**
 * The audio of an MP3, which {@link Mp3Parser#parse} opens in its {@link ParsedMp3}, decoded a frame at a time as it's
 * iterated, to floating point, as MPEG audio has no bit depth of its own. It decodes as FFmpeg does, as AcoustID's fpcalc
 * decodes with it: the frames FFmpeg's parser finds, in Layers I, II and III, with each frame's own channels, as a
 * stream may change between mono and stereo, and FFmpeg's trimming of the encoder's delay and padding a LAME tag gives.
 * Every frame is a {@link FloatPcmFrame} of its own.
 *
 * <p>The iterator throws {@link UncheckedIOException} when the stream can't be read, and
 * {@link UncheckedInvalidFormatException} when a frame's header can't be read.
 *
 * <p>A Layer III frame is read from the file's stream in turn: its header, through the parser, its side information
 * (ISO/IEC 11172-3 section 2.4.1.7, ISO/IEC 13818-3 section 2.4.1.7), and its main data, which may start in the frames
 * before it. Each granule's scale factors and spectrum are decoded for each channel, then its joint stereo, and each
 * channel's reordering, alias reduction, IMDCT and synthesis. The decoder keeps each channel's state from granule to
 * granule, and decodes every frame into the same buffers: only what it reads from the frame is new for each one.
 *
 * <p>Scale factors start each granule's channel in the main data (ISO/IEC 11172-3 section 2.4.2.7, ISO/IEC 13818-3
 * section 2.4.3.2). Both versions code them in the same order, which is then laid out by band. A mixed block's lowest
 * bands are long: 8 in MPEG-1, and 6 in MPEG-2, and its short bands start at band 3.
 */
public final class Mp3Decoder implements FloatPcmAudio {

    private static final int BITS_PER_SAMPLE = 16;
    private static final int MAX_CHANNELS = 2;
    private static final int MAX_SAMPLES_PER_FRAME = 1152;
    // A Layer I frame has 12 instants of subband samples, and a Layer II frame 36.
    private static final int LAYER_I_INSTANTS = 12;
    private static final int LAYER_II_INSTANTS = 36;
    // A granule has 576 values, so at most 288 pairs.
    private static final int MAX_BIG_VALUES = 288;
    // MPEG-2's scale factors use pre-emphasis from this scalefac_compress on, except in intensity stereo's right
    // channel.
    private static final int LSF_PREFLAG_COMPRESS = 500;
    // MPEG-1's short blocks have scale factors for 12 bands, in each window.
    private static final int MPEG_1_SHORT_FACTORS = 12 * Windowing.WINDOWS;
    // How many scale factors take slen1 bits, the rest taking slen2: bands 0 to 10 of a long block, and 0 to 5 of a
    // short one.
    private static final int MPEG_1_LONG_SLEN1_FACTORS = 11;
    private static final int MPEG_1_SHORT_SLEN1_FACTORS = 6 * Windowing.WINDOWS;
    // A mixed block has 8 long bands, then short bands 3 to 11, of which 3 to 5 take slen1 bits.
    private static final int MPEG_1_MIXED_FACTORS = 8 + 9 * Windowing.WINDOWS;
    private static final int MPEG_1_MIXED_SLEN1_FACTORS = 8 + 3 * Windowing.WINDOWS;
    // The groups of long bands MPEG-1's second granule may share with its first: bands 0 to 20.
    private static final int[] SHARED_GROUP_STARTS = {0, 6, 11, 16, 21};
    private static final int FIRST_GROUP_BIT = 0b1000;
    // MPEG-2's right channel of an intensity stereo frame halves scalefac_compress, then splits it at these.
    private static final int LSF_INTENSITY_SPLIT_1 = 180;
    private static final int LSF_INTENSITY_SPLIT_2 = 244;
    // Its other channels split it at these.
    private static final int LSF_SPLIT_1 = 400;
    private static final int LSF_SPLIT_2 = 500;

    private final Mp3Parser parser;
    private final Mp3StreamInfo streamInfo;
    private final BoundedInputStream input;
    private final BitInputStream bits;
    private final BitReservoir reservoir = new BitReservoir();
    // Each channel's scale factors for the last granule read, which stereo decoding takes, and for the last first
    // granule read, which MPEG-1's second granule may share.
    private final ScaleFactors[] scaleFactors = {ScaleFactors.NONE, ScaleFactors.NONE};
    private final ScaleFactors[] firstGranuleFactors = {ScaleFactors.NONE, ScaleFactors.NONE};
    private final Spectrum[] spectra = {new Spectrum(), new Spectrum()};
    private final StereoProcessor stereoProcessor = new StereoProcessor();
    private final Imdct[] imdcts = {new Imdct(), new Imdct()};
    private final SubbandReader subbandReader = new SubbandReader();
    private final PolyphaseSynthesis[] syntheses = {new PolyphaseSynthesis(), new PolyphaseSynthesis()};
    // The samples as FFmpeg makes them in floating point, which a frame copies from.
    private final float[][] samples = new float[MAX_CHANNELS][MAX_SAMPLES_PER_FRAME];
    private Iteration iteration = Iteration.NOT_STARTED;
    // Set once FFmpeg would have returned an error for a frame it dropped, which stops fpcalc reading.
    private FrameContinuity continuity = FrameContinuity.CONTINUOUS;
    // Decoded samples still to skip from the start, as FFmpeg skips them from what it decodes.
    private long leadingSamples;
    // The samples of every frame found so far, decoded or dropped, which is where FFmpeg places its padding.
    private long framePosition;

    /**
     * @param parser the parser that finds and reads each frame's header
     * @param input the file from where the decoder starts, then zeros
     */
    Mp3Decoder(final Mp3Parser parser, final Mp3StreamInfo streamInfo, final BoundedInputStream input) {
        this.parser = parser;
        this.streamInfo = streamInfo;
        this.input = input;
        this.bits = BitInputStream.wrap(input, ByteOrder.BIG_ENDIAN);
        this.leadingSamples = streamInfo.leadingSamples();
    }

    public Mp3StreamInfo streamInfo() {
        return streamInfo;
    }

    @Override
    public int sampleRate() {
        return streamInfo.sampleRate();
    }

    @Override
    public int bitsPerSample() {
        return BITS_PER_SAMPLE;
    }

    /**
     * @throws IllegalStateException when called a second time
     */
    @Override
    public Iterator<FloatPcmFrame> iterator() {
        if (iteration == Iteration.STARTED) {
            throw new IllegalStateException("MP3 decoder can only be iterated once!");
        }
        iteration = Iteration.STARTED;
        return new FrameIterator();
    }

    private enum Progress {
        DECODING,
        ENDED
    }

    // Whether there's a next frame is only known once it's decoded, as FFmpeg may drop or trim a frame whole.
    private final class FrameIterator implements Iterator<FloatPcmFrame> {

        private FloatPcmFrame next;
        private Progress progress = Progress.DECODING;

        @Override
        public boolean hasNext() {
            if (next == null && progress == Progress.DECODING) {
                try {
                    next = decodeNextGivenFrame();
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                } catch (final InvalidFormatException e) {
                    throw new UncheckedInvalidFormatException(e);
                }
                if (next == null) {
                    progress = Progress.ENDED;
                }
            }
            return next != null;
        }

        @Override
        public FloatPcmFrame next() {
            if (!hasNext()) {
                throw new NoSuchElementException("MP3 stream has no more frames!");
            }
            final var frame = next;
            next = null;
            return frame;
        }
    }

    /**
     * @return the next frame with samples to give, or null once the audio has none
     */
    private FloatPcmFrame decodeNextGivenFrame() throws IOException, InvalidFormatException {
        final var audioLength = streamInfo.audioEnd() - streamInfo.audioStart();
        while (parser.findFrame(input, bits, audioLength, streamInfo.freeFormatLength())
                instanceof Mp3Parser.FoundFrame found) {
            final var header = found.header();
            final var position = framePosition;
            framePosition += header.samplesPerFrame();
            final var frameStart = input.getCount() - FrameHeader.SIZE;
            bits.readBits(32);
            final var presentBytes = (int) Math.min(header.frameLength(), audioLength - frameStart);
            final var decoded = found.lead() == Mp3Parser.FrameLead.AFTER_ZEROS && decodeFrame(header, presentBytes);
            if (!decoded) {
                continuity = FrameContinuity.AFTER_DECODING_ERROR;
            }
            skipToEnd(frameStart + header.frameLength());
            if (decoded) {
                final var pcmFrame = trim(header, position);
                if (pcmFrame != null) {
                    return pcmFrame;
                }
            }
        }
        return null;
    }

    // A frame's ancillary data, or all of a frame FFmpeg drops, is skipped. Every frame is whole bytes, so the bit
    // stream holds no bits after it.
    private void skipToEnd(final long frameEnd) throws IOException {
        bits.alignToByte();
        IOUtils.skip(input, frameEnd - input.getCount());
    }

    /**
     * Trims the frame as FFmpeg does: the samples to skip from the start, counting only what it decodes, then the
     * padding from the end, counting every frame it found.
     *
     * @param position where the frame's samples start, counting every frame found before it
     * @return the frame with the samples it gives, or null when it gives none
     */
    private FloatPcmFrame trim(final FrameHeader header, final long position) {
        final var frameSamples = header.samplesPerFrame();
        if (leadingSamples >= frameSamples) {
            leadingSamples -= frameSamples;
            return null;
        }
        final var start = (int) leadingSamples;
        leadingSamples = 0;
        var end = frameSamples;
        final var padding = paddingIn(position, frameSamples);
        if (padding > 0 && padding <= end - start) {
            if (padding == end - start) {
                return null;
            }
            end -= padding;
        }
        return new FloatPcmFrame(copyOfSamples(header.channelCount(), start, end), header.sampleRate(), continuity);
    }

    // The frame keeps its samples, so they're copied out of the buffers the next frame decodes into.
    private float[][] copyOfSamples(final int channelCount, final int start, final int end) {
        final var copy = new float[channelCount][];
        for (var channel = 0; channel < channelCount; channel++) {
            copy[channel] = Arrays.copyOfRange(samples[channel], start, end);
        }
        return copy;
    }

    // How many samples at the end of the frame are the encoder's padding.
    private int paddingIn(final long position, final int frameSamples) {
        final var frameEnd = position + frameSamples;
        if (frameEnd > streamInfo.paddingStart() && position < streamInfo.paddingEnd()) {
            return (int) Math.min(frameEnd - streamInfo.paddingStart(), frameSamples);
        }
        return 0;
    }

    /**
     * Decodes the frame whose header was just read.
     *
     * @return whether it was decoded: FFmpeg drops a Layer III frame whose side information has more big values than a
     *     granule's lines, or switches windows to a normal block
     */
    private boolean decodeFrame(final FrameHeader header, final int presentBytes)
            throws IOException, InvalidFormatException {
        // The CRC isn't checked: a frame that fails it is still decoded, as other decoders do.
        IOUtils.skip(input, header.protection().crcSize());
        return switch (header.layer()) {
            case I -> {
                subbandReader.readLayerI(bits, header);
                synthesizeSubbands(header, LAYER_I_INSTANTS);
                yield true;
            }
            case II -> {
                subbandReader.readLayerII(bits, header);
                synthesizeSubbands(header, LAYER_II_INSTANTS);
                yield true;
            }
            case III -> decodeLayerIII(header, presentBytes);
        };
    }

    private void synthesizeSubbands(final FrameHeader header, final int instants) {
        for (var channel = 0; channel < header.channelCount(); channel++) {
            syntheses[channel].synthesize(subbandReader.channel(channel), instants);
            toSamples(channel, 0, instants * Spectrum.SUBBANDS);
        }
    }

    // A frame whose side information FFmpeg rejects leaves its main data alone in the reservoir.
    private boolean decodeLayerIII(final FrameHeader header, final int presentBytes) throws IOException {
        final var sideInfoStart = input.getCount();
        final SideInfo sideInfo;
        try {
            sideInfo = readSideInfo(header);
        } catch (final InvalidFormatException rejected) {
            bits.alignToByte();
            IOUtils.skip(input, sideInfoStart + header.sideInfoSize() - input.getCount());
            reservoir.restart(input, header);
            return false;
        }
        final var frame = reservoir.readMainData(input, header, sideInfo, presentBytes);
        reservoir.keepAfterGranules(frame, decodeGranules(frame));
        return true;
    }

    /**
     * Decodes the frame's granules, each channel's from where its bits start, so one channel's bits running out leaves
     * the next where it starts, as FFmpeg reads them. A granule whose bits start before the stream's main data, as when
     * a stream is cut from a longer one, is decoded as silence, as FFmpeg drops it.
     *
     * @return how many bits the granules take
     */
    private int decodeGranules(final Mp3Frame frame) throws IOException {
        final var header = frame.header();
        final var sideInfo = frame.sideInfo();
        var offset = 0;
        for (var granule = 0; granule < header.granuleCount(); granule++) {
            final var granuleChannels = sideInfo.granules().get(granule);
            final var readable = frame.granulesStart() + offset / Byte.SIZE >= 0;
            for (var channel = 0; channel < header.channelCount(); channel++) {
                if (readable) {
                    readChannel(frame, granule, channel, offset);
                } else {
                    spectra[channel].clear();
                }
                offset += granuleChannels.get(channel).part23Length();
            }
            if (readable && header.channelCount() == MAX_CHANNELS) {
                stereoProcessor.processStereo(header, spectra[0], spectra[1], granuleChannels.get(1), scaleFactors[1]);
            }
            for (var channel = 0; channel < header.channelCount(); channel++) {
                synthesizeGranule(header, granuleChannels.get(channel).windowing(), channel);
                toSamples(channel, granule * FrameHeader.GRANULE_SIZE, FrameHeader.GRANULE_SIZE);
            }
        }
        return offset;
    }

    // Each channel's bits start with its scale factors, and its values follow, until its part2_3_length is used up.
    private void readChannel(final Mp3Frame frame, final int granule, final int channel, final int offset)
            throws IOException {
        final var channelBits = reservoir.bitsAt(frame, offset);
        final var granuleChannel = frame.sideInfo().granules().get(granule).get(channel);
        final var channelFactors = readScaleFactors(channelBits, frame.header(), granuleChannel, granule, channel);
        final var presentBits = reservoir.presentBitsAt(frame, offset);
        spectra[channel].read(channelBits, frame.header(), granuleChannel, channelFactors, presentBits);
    }

    private void synthesizeGranule(final FrameHeader header, final Windowing windowing, final int channel) {
        final var spectrum = spectra[channel];
        spectrum.reorderShortBands(header, windowing);
        spectrum.reduceAliasing(windowing);
        imdcts[channel].transform(spectrum, windowing);
        syntheses[channel].synthesize(imdcts[channel], Spectrum.SUBBAND_LINES);
    }

    private void toSamples(final int channel, final int first, final int count) {
        final var synthesis = syntheses[channel];
        for (var sample = 0; sample < count; sample++) {
            samples[channel][first + sample] = synthesis.sample(sample);
        }
    }

    /**
     * Reads a channel's scale factors for a granule. MPEG-1's second granule may keep the first's for groups of long
     * bands: those of the last first granule read, as FFmpeg keeps them when a frame's first granule is dropped.
     *
     * @param channel 0 for the left or only channel, and 1 for the right
     */
    private ScaleFactors readScaleFactors(
            final BitInputStream channelBits,
            final FrameHeader header,
            final GranuleChannel granuleChannel,
            final int granule,
            final int channel)
            throws IOException {
        scaleFactors[channel] = switch (header.version()) {
            case MPEG_1 -> readMpeg1Factors(channelBits, granuleChannel, firstGranuleFactors[channel]);
            case MPEG_2, MPEG_2_5 -> readLsfFactors(channelBits, header, granuleChannel, channel);
        };
        if (granule == 0) {
            firstGranuleFactors[channel] = scaleFactors[channel];
        }
        return scaleFactors[channel];
    }

    private ScaleFactors readMpeg1Factors(
            final BitInputStream mainData, final GranuleChannel granuleChannel, final ScaleFactors previous)
            throws IOException {
        final var compress = granuleChannel.scalefacCompress();
        final var slen1 = ScaleFactorTables.mpeg1Slen1(compress);
        final var slen2 = ScaleFactorTables.mpeg1Slen2(compress);
        final var layout = granuleChannel.windowing().layout();
        return switch (layout) {
            case LONG -> readMpeg1LongFactors(mainData, granuleChannel.scaleFactorSharing(), previous, slen1, slen2);
            case SHORT ->
                readMpeg1ShortFactors(mainData, layout, MPEG_1_SHORT_FACTORS, MPEG_1_SHORT_SLEN1_FACTORS, slen1, slen2);
            case MIXED ->
                readMpeg1ShortFactors(mainData, layout, MPEG_1_MIXED_FACTORS, MPEG_1_MIXED_SLEN1_FACTORS, slen1, slen2);
        };
    }

    // Each group whose scfsi bit is set keeps the first granule's scale factors, and the rest are read. The first
    // group's bit is the highest.
    private ScaleFactors readMpeg1LongFactors(
            final BitInputStream mainData,
            final int sharing,
            final ScaleFactors previous,
            final int slen1,
            final int slen2)
            throws IOException {
        final var factors = new int[ScaleFactors.SIZE];
        var part2Length = 0;
        for (var group = 0; group < SHARED_GROUP_STARTS.length - 1; group++) {
            final var shared = (sharing & (FIRST_GROUP_BIT >> group)) != 0;
            for (var band = SHARED_GROUP_STARTS[group]; band < SHARED_GROUP_STARTS[group + 1]; band++) {
                if (shared) {
                    factors[band] = previous.longFactor(band);
                } else {
                    final var slen = slen(band, MPEG_1_LONG_SLEN1_FACTORS, slen1, slen2);
                    factors[band] = readFactor(mainData, slen);
                    part2Length += slen;
                }
            }
        }
        return new ScaleFactors(factors, part2Length);
    }

    private ScaleFactors readMpeg1ShortFactors(
            final BitInputStream mainData,
            final BlockLayout layout,
            final int count,
            final int slen1Count,
            final int slen1,
            final int slen2)
            throws IOException {
        final var factors = new int[ScaleFactors.SIZE];
        var part2Length = 0;
        for (var index = 0; index < count; index++) {
            final var slen = slen(index, slen1Count, slen1, slen2);
            final var slot = slot(index, layout, MpegVersion.MPEG_1.mixedLongBands());
            factors[slot] = readFactor(mainData, slen);
            part2Length += slen;
        }
        return new ScaleFactors(factors, part2Length);
    }

    private int slen(final int index, final int slen1Count, final int slen1, final int slen2) {
        if (index < slen1Count) {
            return slen1;
        }
        return slen2;
    }

    // Each partition's scale factors take the same number of bits (ISO/IEC 13818-3 section 2.4.3.2).
    private ScaleFactors readLsfFactors(
            final BitInputStream mainData,
            final FrameHeader header,
            final GranuleChannel granuleChannel,
            final int channel)
            throws IOException {
        final var lengths = lsfLengths(header, channel, granuleChannel.scalefacCompress());
        final var layout = granuleChannel.windowing().layout();
        final var mixedLongBands = header.version().mixedLongBands();
        final var factors = new int[ScaleFactors.SIZE];
        var part2Length = 0;
        var index = 0;
        for (var partition = 0; partition < ScaleFactorTables.LSF_PARTITIONS; partition++) {
            final var count = ScaleFactorTables.lsfBandCount(lengths.table(), layout, partition);
            final var slen = lengths.slen(partition);
            for (var factor = 0; factor < count; factor++) {
                final var slot = slot(index++, layout, mixedLongBands);
                factors[slot] = readFactor(mainData, slen);
                part2Length += slen;
            }
        }
        return new ScaleFactors(factors, part2Length);
    }

    /**
     * @param channel 0 for the left or only channel, and 1 for the right
     */
    private LsfLengths lsfLengths(final FrameHeader header, final int channel, final int scalefacCompress) {
        if (header.isIntensityStereo() && channel == 1) {
            return intensityLsfLengths(scalefacCompress >> 1);
        }
        return lsfLengths(scalefacCompress);
    }

    private LsfLengths lsfLengths(final int compress) {
        if (compress < LSF_SPLIT_1) {
            return new LsfLengths(
                    0, new int[] {(compress >> 4) / 5, (compress >> 4) % 5, (compress & 15) >> 2, compress & 3});
        }
        if (compress < LSF_SPLIT_2) {
            final var offset = compress - LSF_SPLIT_1;
            return new LsfLengths(1, new int[] {(offset >> 2) / 5, (offset >> 2) % 5, offset & 3, 0});
        }
        final var offset = compress - LSF_SPLIT_2;
        return new LsfLengths(2, new int[] {offset / 3, offset % 3, 0, 0});
    }

    private LsfLengths intensityLsfLengths(final int compress) {
        if (compress < LSF_INTENSITY_SPLIT_1) {
            return new LsfLengths(3, new int[] {compress / 36, compress % 36 / 6, compress % 36 % 6, 0});
        }
        if (compress < LSF_INTENSITY_SPLIT_2) {
            final var offset = compress - LSF_INTENSITY_SPLIT_1;
            return new LsfLengths(4, new int[] {(offset & 63) >> 4, (offset & 15) >> 2, offset & 3, 0});
        }
        final var offset = compress - LSF_INTENSITY_SPLIT_2;
        return new LsfLengths(5, new int[] {offset / 3, offset % 3, 0, 0});
    }

    // A scale factor of no bits is 0.
    private int readFactor(final BitInputStream mainData, final int slen) throws IOException {
        if (slen == 0) {
            return 0;
        }
        return (int) mainData.readBits(slen);
    }

    // Where the scale factor coded at the index goes: the layout's long bands come first, then its short bands by band
    // and window.
    private int slot(final int index, final BlockLayout layout, final int mixedLongBands) {
        final var longCount = switch (layout) {
            case LONG -> ScaleFactors.SIZE;
            case SHORT -> 0;
            case MIXED -> mixedLongBands;
        };
        if (index < longCount) {
            return index;
        }
        return ScaleFactorBands.LONG_BANDS + firstShortBand(layout) * Windowing.WINDOWS + index - longCount;
    }

    private int firstShortBand(final BlockLayout layout) {
        return switch (layout) {
            case LONG, SHORT -> 0;
            case MIXED -> ScaleFactors.MIXED_FIRST_SHORT_BAND;
        };
    }

    private SideInfo readSideInfo(final FrameHeader header) throws IOException, InvalidFormatException {
        final var mainDataBegin = (int) bits.readBits(mainDataBeginBits(header.version()));
        // The private bits are the encoder's own.
        bits.readBits(privateBits(header));
        final var channelSharing = new int[header.channelCount()];
        for (var channel = 0; channel < channelSharing.length; channel++) {
            channelSharing[channel] = readChannelSharing(header.version());
        }
        final var granules = new ArrayList<List<GranuleChannel>>();
        for (var granule = 0; granule < header.granuleCount(); granule++) {
            final var channels = new ArrayList<GranuleChannel>();
            for (var channel = 0; channel < header.channelCount(); channel++) {
                channels.add(readGranuleChannel(header, channel, granuleSharing(granule, channelSharing[channel])));
            }
            granules.add(List.copyOf(channels));
        }
        return new SideInfo(mainDataBegin, List.copyOf(granules));
    }

    private int mainDataBeginBits(final MpegVersion version) {
        return switch (version) {
            case MPEG_1 -> 9;
            case MPEG_2, MPEG_2_5 -> 8;
        };
    }

    private int privateBits(final FrameHeader header) {
        return switch (header.channelMode()) {
            case MONO -> monoPrivateBits(header.version());
            case STEREO, JOINT_STEREO, DUAL_CHANNEL -> stereoPrivateBits(header.version());
        };
    }

    private int monoPrivateBits(final MpegVersion version) {
        return switch (version) {
            case MPEG_1 -> 5;
            case MPEG_2, MPEG_2_5 -> 1;
        };
    }

    private int stereoPrivateBits(final MpegVersion version) {
        return switch (version) {
            case MPEG_1 -> 3;
            case MPEG_2, MPEG_2_5 -> 2;
        };
    }

    // MPEG-1's scfsi, four bits per channel, which only the second granule uses. MPEG-2 has none.
    private int readChannelSharing(final MpegVersion version) throws IOException {
        return switch (version) {
            case MPEG_1 -> (int) bits.readBits(4);
            case MPEG_2, MPEG_2_5 -> 0;
        };
    }

    // The first granule has no granule before it to share with.
    private int granuleSharing(final int granule, final int channelSharing) {
        if (granule == 0) {
            return 0;
        }
        return channelSharing;
    }

    /**
     * @param channel 0 for the left or only channel, and 1 for the right
     * @param sharing MPEG-1's scfsi for the second granule, or 0 otherwise
     */
    private GranuleChannel readGranuleChannel(final FrameHeader header, final int channel, final int sharing)
            throws IOException, InvalidFormatException {
        final var part23Length = (int) bits.readBits(12);
        final var bigValues = (int) bits.readBits(9);
        if (bigValues > MAX_BIG_VALUES) {
            throw new InvalidFormatException("MP3 granule has %d pairs of big values!".formatted(bigValues));
        }
        final var globalGain = (int) bits.readBits(8);
        final var scalefacCompress = (int) bits.readBits(scalefacCompressBits(header.version()));
        final var windowing = readWindowing();
        final var preflag = readPreflag(header, channel, scalefacCompress);
        final var scalefacScale = (int) bits.readBits(1);
        final var count1Code = count1Code(bits.readBits(1));
        return new GranuleChannel(
                part23Length,
                bigValues,
                globalGain,
                scalefacCompress,
                windowing,
                preflag,
                scalefacScale,
                count1Code,
                sharing);
    }

    private int scalefacCompressBits(final MpegVersion version) {
        return switch (version) {
            case MPEG_1 -> 4;
            case MPEG_2, MPEG_2_5 -> 9;
        };
    }

    // The window_switching_flag says which kind follows.
    private Windowing readWindowing() throws IOException, InvalidFormatException {
        if (bits.readBits(1) == 0) {
            return readNormalWindowing();
        }
        return readSwitchedWindowing();
    }

    private Windowing readNormalWindowing() throws IOException {
        final var table0 = readTable();
        final var table1 = readTable();
        final var table2 = readTable();
        final var region0Count = (int) bits.readBits(4);
        final var region1Count = (int) bits.readBits(3);
        return new Windowing.Normal(table0, table1, table2, region0Count, region1Count);
    }

    private Windowing readSwitchedWindowing() throws IOException, InvalidFormatException {
        final var blockType = BlockType.fromSwitchedBits((int) bits.readBits(2));
        final var lowSubbands = readLowSubbands();
        final var table0 = readTable();
        final var table1 = readTable();
        final var subblockGains = new int[Windowing.WINDOWS];
        for (var window = 0; window < subblockGains.length; window++) {
            subblockGains[window] = (int) bits.readBits(3);
        }
        return new Windowing.Switched(blockType, lowSubbands, table0, table1, subblockGains);
    }

    // The mixed_block_flag.
    private LowSubbands readLowSubbands() throws IOException {
        if (bits.readBits(1) == 1) {
            return LowSubbands.NORMAL_WINDOW;
        }
        return LowSubbands.BY_BLOCK_TYPE;
    }

    private int readTable() throws IOException {
        return (int) bits.readBits(5);
    }

    private int readPreflag(final FrameHeader header, final int channel, final int scalefacCompress)
            throws IOException {
        return switch (header.version()) {
            case MPEG_1 -> (int) bits.readBits(1);
            case MPEG_2, MPEG_2_5 -> lsfPreflag(header, channel, scalefacCompress);
        };
    }

    private int lsfPreflag(final FrameHeader header, final int channel, final int scalefacCompress) {
        if (header.isIntensityStereo() && channel == 1) {
            return 0;
        }
        if (scalefacCompress >= LSF_PREFLAG_COMPRESS) {
            return 1;
        }
        return 0;
    }

    private HuffmanCode count1Code(final long count1TableSelect) {
        if (count1TableSelect == 0) {
            return HuffmanTables.COUNT1_A;
        }
        return HuffmanTables.COUNT1_B;
    }
}
