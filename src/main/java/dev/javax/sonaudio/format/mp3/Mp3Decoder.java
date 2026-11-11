package dev.javax.sonaudio.format.mp3;

import dev.javax.bitstream.BitInputStream;
import dev.javax.sonaudio.format.FloatPcmAudio;
import dev.javax.sonaudio.format.FloatPcmFrame;
import dev.javax.sonaudio.format.FrameContinuity;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * The audio of an MP3, which {@link Mp3Parser#parse} opens in its {@link ParsedMp3}, decoded a frame at a time as each
 * is asked for, to floating point, as MPEG audio has no bit depth of its own. It decodes as FFmpeg does, as AcoustID's fpcalc
 * decodes with it: the frames FFmpeg's parser finds, in Layers I, II and III, with each frame's own channels, as a
 * stream may change between mono and stereo, and FFmpeg's trimming of the encoder's delay and padding a LAME tag gives.
 * Every frame is a {@link FloatPcmFrame} of its own.
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
    // A window_switching_flag of 1, then a block_type of 0, which FFmpeg rejects.
    private static final long SWITCHED_TO_NORMAL_BLOCK = 0b100;
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
    // Whether there's a next frame is only known once it's decoded, as FFmpeg may drop or trim a frame whole, so
    // hasNextFrame decodes it, and nextFrame gives it.
    private NextFrame nextFrame = new Undecoded();
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

    /**
     * @return what the parser found of the stream before its audio
     */
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

    @Override
    public boolean hasNextFrame() throws IOException {
        return switch (peekNextFrame()) {
            case Decoded _ -> true;
            case Undecoded _, Ended _ -> false;
        };
    }

    @Override
    public FloatPcmFrame nextFrame() throws IOException {
        return switch (peekNextFrame()) {
            case Decoded(final var frame) -> {
                nextFrame = new Undecoded();
                yield frame;
            }
            case Undecoded _, Ended _ -> throw new NoSuchElementException("MP3 stream has no more frames!");
        };
    }

    private NextFrame peekNextFrame() throws IOException {
        nextFrame = switch (nextFrame) {
            case Undecoded _ -> decodeNextGivenFrame();
            case Decoded _, Ended _ -> nextFrame;
        };
        return nextFrame;
    }

    /**
     * The frame after those given: not decoded yet, decoded and waiting to be given, or none, once the audio ends.
     */
    private sealed interface NextFrame permits Undecoded, Decoded, Ended {}

    private record Undecoded() implements NextFrame {}

    private record Decoded(FloatPcmFrame frame) implements NextFrame {}

    private record Ended() implements NextFrame {}

    private NextFrame decodeNextGivenFrame() throws IOException {
        final var audioLength = streamInfo.audioEnd() - streamInfo.audioStart();
        while (parser.findFrame(input, bits, audioLength, streamInfo.freeFormatLength())
                instanceof Mp3Parser.FoundFrame found) {
            final var frameSamples = decodeFoundFrame(found, audioLength);
            switch (frameSamples) {
                case Samples(final var frame) -> {
                    return new Decoded(frame);
                }
                case NoSamples _ -> {}
            }
        }
        return new Ended();
    }

    // A frame FFmpeg drops gives no samples, and those after it follow a decoding error.
    private FrameSamples decodeFoundFrame(final Mp3Parser.FoundFrame found, final long audioLength) throws IOException {
        final var header = found.header();
        final var position = framePosition;
        framePosition += header.samplesPerFrame();
        final var frameStart = input.getCount() - FrameHeader.SIZE;
        bits.readBits(32);
        final var presentBytes = (int) Math.min(header.frameLength(), audioLength - frameStart);
        final var decoded = found.lead() == Mp3Parser.FrameLead.AFTER_ZEROS && decodeFrame(header, presentBytes);
        skipToEnd(frameStart + header.frameLength());
        if (!decoded) {
            continuity = FrameContinuity.AFTER_DECODING_ERROR;
            return new NoSamples();
        }
        return trimFrame(header, position);
    }

    /**
     * What's left of a decoded frame once it's trimmed: its samples, or none.
     */
    private sealed interface FrameSamples permits Samples, NoSamples {}

    private record Samples(FloatPcmFrame frame) implements FrameSamples {}

    private record NoSamples() implements FrameSamples {}

    // A frame's ancillary data, or all of a frame FFmpeg drops, is skipped. Every frame is whole bytes, so the bit
    // stream holds no bits after it.
    private void skipToEnd(final long frameEnd) throws IOException {
        bits.alignToByte();
        input.skipAvailableBytes(frameEnd - input.getCount());
    }

    /**
     * Trims the frame as FFmpeg does: the samples to skip from the start, counting only what it decodes, then the
     * padding from the end, counting every frame it found.
     *
     * @param position where the frame's samples start, counting every frame found before it
     */
    private FrameSamples trimFrame(final FrameHeader header, final long position) {
        final var frameSamples = header.samplesPerFrame();
        if (leadingSamples >= frameSamples) {
            leadingSamples -= frameSamples;
            return new NoSamples();
        }
        final var start = (int) leadingSamples;
        leadingSamples = 0;
        final var padding = paddingSamples(position, frameSamples);
        if (padding == frameSamples - start) {
            return new NoSamples();
        }
        final var end = trimmedEnd(frameSamples, start, padding);
        final var frameCopy = copyOfSamples(header.channelCount(), start, end);
        return new Samples(new FloatPcmFrame(frameCopy, header.sampleRate(), continuity));
    }

    // FFmpeg trims the padding only when it fits in what's left of the frame.
    private int trimmedEnd(final int frameSamples, final int start, final int padding) {
        if (padding > frameSamples - start) {
            return frameSamples;
        }
        return frameSamples - padding;
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
    private int paddingSamples(final long position, final int frameSamples) {
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
    private boolean decodeFrame(final FrameHeader header, final int presentBytes) throws IOException {
        // The CRC isn't checked: a frame that fails it is still decoded, as other decoders do.
        input.skipAvailableBytes(header.protection().crcSize());
        return switch (header.layer()) {
            case I -> decodeLayerI(header);
            case II -> decodeLayerII(header);
            case III -> decodeLayerIII(header, presentBytes);
        };
    }

    private boolean decodeLayerI(final FrameHeader header) throws IOException {
        subbandReader.readLayerI(bits, header);
        synthesizeSubbands(header, LAYER_I_INSTANTS);
        return true;
    }

    private boolean decodeLayerII(final FrameHeader header) throws IOException {
        subbandReader.readLayerII(bits, header);
        synthesizeSubbands(header, LAYER_II_INSTANTS);
        return true;
    }

    private void synthesizeSubbands(final FrameHeader header, final int instants) {
        for (var channel = 0; channel < header.channelCount(); channel++) {
            syntheses[channel].synthesizeInstants(subbandReader.channel(channel), instants);
            toSamples(channel, 0, instants * Spectrum.SUBBANDS);
        }
    }

    // A frame whose side information FFmpeg rejects leaves its main data alone in the reservoir.
    private boolean decodeLayerIII(final FrameHeader header, final int presentBytes) throws IOException {
        final var sideInfoStart = input.getCount();
        return switch (readSideInfo(header)) {
            case AcceptedSideInfo(final var sideInfo) -> decodeMainData(header, sideInfo, presentBytes);
            case RejectedSideInfo _ -> skipRejectedFrame(header, sideInfoStart);
        };
    }

    private boolean decodeMainData(final FrameHeader header, final SideInfo sideInfo, final int presentBytes)
            throws IOException {
        final var frame = reservoir.readMainData(input, header, sideInfo, presentBytes);
        reservoir.keepAfterGranules(frame, decodeGranules(frame));
        return true;
    }

    private boolean skipRejectedFrame(final FrameHeader header, final long sideInfoStart) throws IOException {
        bits.alignToByte();
        input.skipAvailableBytes(sideInfoStart + header.sideInfoSize() - input.getCount());
        reservoir.restartWithFrame(input, header);
        return false;
    }

    /**
     * Decodes the frame's granules, each channel's from where its bits start, so one channel's bits running out leaves
     * the next where it starts, as FFmpeg reads them.
     *
     * @return how many bits the granules take
     */
    private int decodeGranules(final Mp3Frame frame) throws IOException {
        var offset = 0;
        for (var granule = 0; granule < frame.header().granuleCount(); granule++) {
            offset = decodeGranule(frame, granule, offset);
        }
        return offset;
    }

    /**
     * @param offset how many bits the granules before this one take
     * @return how many bits the granules up to this one take
     */
    private int decodeGranule(final Mp3Frame frame, final int granule, final int offset) throws IOException {
        final var header = frame.header();
        final var granuleChannels = frame.sideInfo().granules().get(granule);
        final var granuleStart = granuleStart(frame, offset);
        final var end = readGranuleChannels(frame, granule, offset, granuleStart);
        if (granuleStart == GranuleStart.IN_RESERVOIR && header.channelCount() == MAX_CHANNELS) {
            stereoProcessor.processStereo(header, spectra[0], spectra[1], granuleChannels.get(1), scaleFactors[1]);
        }
        for (var channel = 0; channel < header.channelCount(); channel++) {
            synthesizeGranule(header, granuleChannels.get(channel).windowing(), channel);
            toSamples(channel, granule * FrameHeader.GRANULE_SIZE, FrameHeader.GRANULE_SIZE);
        }
        return end;
    }

    /**
     * Where a granule's bits start: in the reservoir, or before the stream's main data, as when a stream is cut from a
     * longer one, which makes it silent, as FFmpeg drops it.
     */
    private enum GranuleStart {
        IN_RESERVOIR,
        BEFORE_STREAM
    }

    private GranuleStart granuleStart(final Mp3Frame frame, final int offset) {
        if (frame.granulesStart() + offset / Byte.SIZE >= 0) {
            return GranuleStart.IN_RESERVOIR;
        }
        return GranuleStart.BEFORE_STREAM;
    }

    /**
     * @return how many bits the granules up to this one take
     */
    private int readGranuleChannels(
            final Mp3Frame frame, final int granule, final int offset, final GranuleStart granuleStart)
            throws IOException {
        final var granuleChannels = frame.sideInfo().granules().get(granule);
        var channelOffset = offset;
        for (var channel = 0; channel < frame.header().channelCount(); channel++) {
            switch (granuleStart) {
                case IN_RESERVOIR -> readChannel(frame, granule, channel, channelOffset);
                case BEFORE_STREAM -> spectra[channel].clearLines();
            }
            channelOffset += granuleChannels.get(channel).part23Length();
        }
        return channelOffset;
    }

    // Each channel's bits start with its scale factors, and its values follow, until its part2_3_length is used up.
    private void readChannel(final Mp3Frame frame, final int granule, final int channel, final int offset)
            throws IOException {
        final var channelBits = reservoir.granuleBits(frame, offset);
        final var granuleChannel = frame.sideInfo().granules().get(granule).get(channel);
        final var channelFactors = readScaleFactors(channelBits, frame.header(), granuleChannel, granule, channel);
        final var presentBits = reservoir.presentGranuleBits(frame, offset);
        spectra[channel].readGranule(channelBits, frame.header(), granuleChannel, channelFactors, presentBits);
    }

    private void synthesizeGranule(final FrameHeader header, final Windowing windowing, final int channel) {
        final var spectrum = spectra[channel];
        spectrum.reorderShortBands(header, windowing);
        spectrum.reduceAliasing(windowing);
        imdcts[channel].transformGranule(spectrum, windowing);
        syntheses[channel].synthesizeInstants(imdcts[channel], Spectrum.SUBBAND_LINES);
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

    // Each group whose scfsi bit is set keeps the first granule's scale factors, and the rest are read.
    private ScaleFactors readMpeg1LongFactors(
            final BitInputStream mainData,
            final int sharing,
            final ScaleFactors previous,
            final int slen1,
            final int slen2)
            throws IOException {
        final var factors = new int[ScaleFactors.SIZE];
        var part2Length = 0;
        for (var band = 0; band < SHARED_GROUP_STARTS[SHARED_GROUP_STARTS.length - 1]; band++) {
            if (isSharedBand(sharing, band)) {
                factors[band] = previous.longFactor(band);
                continue;
            }
            final var slen = slen(band, MPEG_1_LONG_SLEN1_FACTORS, slen1, slen2);
            factors[band] = readFactor(mainData, slen);
            part2Length += slen;
        }
        return new ScaleFactors(factors, part2Length);
    }

    // The first group's scfsi bit is the highest.
    private boolean isSharedBand(final int sharing, final int band) {
        var group = 0;
        while (band >= SHARED_GROUP_STARTS[group + 1]) {
            group++;
        }
        return (sharing & (FIRST_GROUP_BIT >> group)) != 0;
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
        return ScaleFactorBands.LONG_BANDS + layout.firstShortBand() * Windowing.WINDOWS + index - longCount;
    }

    // A granule FFmpeg rejects rejects the frame, so the rest of its side information isn't read.
    private SideInfoRead readSideInfo(final FrameHeader header) throws IOException {
        final var mainDataBegin = (int) bits.readBits(mainDataBeginBits(header.version()));
        // The private bits are the encoder's own.
        bits.readBits(privateBits(header));
        final var channelSharing = readChannelSharing(header);
        final var granules = new ArrayList<List<GranuleChannel>>();
        for (var granule = 0; granule < header.granuleCount(); granule++) {
            switch (readGranule(header, granule, channelSharing)) {
                case AcceptedGranule(final var channels) -> granules.add(channels);
                case RejectedSideInfo rejected -> {
                    return rejected;
                }
            }
        }
        return new AcceptedSideInfo(new SideInfo(mainDataBegin, List.copyOf(granules)));
    }

    private GranuleRead readGranule(final FrameHeader header, final int granule, final int[] channelSharing)
            throws IOException {
        final var channels = new ArrayList<GranuleChannel>();
        for (var channel = 0; channel < header.channelCount(); channel++) {
            final var sharing = granuleSharing(granule, channelSharing[channel]);
            switch (readGranuleChannel(header, channel, sharing)) {
                case AcceptedChannel(final var granuleChannel) -> channels.add(granuleChannel);
                case RejectedSideInfo rejected -> {
                    return rejected;
                }
            }
        }
        return new AcceptedGranule(List.copyOf(channels));
    }

    /**
     * Side information as FFmpeg reads it: accepted, or rejected, which drops its frame.
     */
    private sealed interface SideInfoRead permits AcceptedSideInfo, RejectedSideInfo {}

    private sealed interface GranuleRead permits AcceptedGranule, RejectedSideInfo {}

    private sealed interface ChannelRead permits AcceptedChannel, RejectedSideInfo {}

    private record AcceptedSideInfo(SideInfo sideInfo) implements SideInfoRead {}

    private record AcceptedGranule(List<GranuleChannel> channels) implements GranuleRead {}

    private record AcceptedChannel(GranuleChannel channel) implements ChannelRead {}

    /**
     * A granule's channel has more big values than a granule's lines, or switches windows to a normal block.
     */
    private record RejectedSideInfo() implements SideInfoRead, GranuleRead, ChannelRead {}

    // MPEG-1's scfsi, four bits per channel, which only the second granule uses.
    private int[] readChannelSharing(final FrameHeader header) throws IOException {
        final var channelSharing = new int[header.channelCount()];
        for (var channel = 0; channel < channelSharing.length; channel++) {
            channelSharing[channel] = readChannelSharing(header.version());
        }
        return channelSharing;
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
    private ChannelRead readGranuleChannel(final FrameHeader header, final int channel, final int sharing)
            throws IOException {
        final var part23Length = (int) bits.readBits(12);
        final var bigValues = (int) bits.readBits(9);
        final var globalGain = (int) bits.readBits(8);
        final var scalefacCompress = (int) bits.readBits(scalefacCompressBits(header.version()));
        if (bigValues > MAX_BIG_VALUES || bits.peekBits(3) == SWITCHED_TO_NORMAL_BLOCK) {
            return new RejectedSideInfo();
        }
        final var windowing = readWindowing();
        final var preflag = readPreflag(header, channel, scalefacCompress);
        final var scalefacScale = (int) bits.readBits(1);
        final var count1Code = count1Code(bits.readBits(1));
        return new AcceptedChannel(new GranuleChannel(
                part23Length,
                bigValues,
                globalGain,
                scalefacCompress,
                windowing,
                preflag,
                scalefacScale,
                count1Code,
                sharing));
    }

    private int scalefacCompressBits(final MpegVersion version) {
        return switch (version) {
            case MPEG_1 -> 4;
            case MPEG_2, MPEG_2_5 -> 9;
        };
    }

    // The window_switching_flag says which kind follows.
    private Windowing readWindowing() throws IOException {
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

    private Windowing readSwitchedWindowing() throws IOException {
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
