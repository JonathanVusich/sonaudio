package dev.javax.sonaudio.format.opus;

import dev.javax.sonaudio.exception.InvalidFormatException;

/**
 * The SILK layer of one coded stream (RFC 6716 section 4.2), which decodes a frame's SILK part to samples at SILK's own
 * rate. {@link OpusStream} drives it once for each Opus frame of a SILK-only or hybrid packet, on the same
 * {@link RangeDecoder} that CELT's layer then continues on in a hybrid frame, and resamples what it writes.
 *
 * <p>It reproduces FFmpeg's decoder ({@code ff_silk_decode_superframe} with {@code ff_silk_flush} in {@code silk.c}),
 * which is a floating-point implementation: the parameters are decoded in integers, as the RFC has them, but the
 * filters run in floats, with FFmpeg's clipping and the order in which it sums, so that the samples come out as
 * FFmpeg's do and not as the RFC's fixed-point reference decoder's. Where FFmpeg does something the RFC doesn't (it
 * decodes and drops the low bit rate redundancy frames, and delays its output by two samples) so does this.
 *
 * <p>State kept from frame to frame, which {@link #reset} forgets: for each of the two coded channels the last frame's
 * decoded history (the output and the filter's state, the pitch lag, gain and filter of the previous frame), the
 * stereo prediction weights of the previous frame, and how many channels were coded in the frame before, so a stream
 * that goes from mono to stereo starts the side channel from nothing.
 */
final class SilkDecoder {

    /**
     * The most samples per channel a frame writes: 60 ms at 16 kHz.
     */
    static final int MAX_FRAME_SAMPLES = 960;

    private static final int MODEL_BITS = 8;
    private static final int MAX_FRAME_MILLIS = 60;
    private static final int TWO_FRAME_MILLIS = 40;
    private static final int LONGEST_FRAME_MILLIS = 20;
    private static final int ABSOLUTE_GAIN_FLOOR = 16;
    private static final int GAIN_DELTA_BASE = 4;
    private static final int GAIN_DELTA_FLOOR = 16;
    private static final int MAX_LOG_GAIN = 63;
    private static final int PITCH_DELTA_BIAS = 9;
    private static final int LTP_TAPS = 5;
    private static final float LTP_TAP_UNIT = 128.0f;
    private static final float LTP_SCALE_UNIT = 16384.0f;
    private static final float DEFAULT_LTP_SCALE = 15565.0f / 16384.0f;
    private static final int STEREO_WEIGHT_FRACTION = 6554;
    private static final double STEREO_WEIGHT_UNIT = 8192.0;
    private static final int STEREO_WEIGHT_STEPS = 5;
    private static final int STEREO_WEIGHT_GROUP = 3;
    // FFmpeg's output is that of the two samples before the frame, which it has moved the history on from.
    private static final int OUTPUT_DELAY = 2;
    private static final int GAIN_LOW_BITS = 3;
    private static final int LOG_GAIN_SCALE = 0x1D1C71;
    private static final int LOG_GAIN_OFFSET = 2090;
    private static final float LINEAR_GAIN_UNIT = 65536.0f;

    // The frame type, which gives the signal type and the quantisation offset in one symbol.
    private record FrameType(SilkSignalType signalType, QuantizationOffset offset) {}

    private final int outputChannels;
    private final SilkChannelState[] channels = {new SilkChannelState(), new SilkChannelState()};
    private final SilkLpcDecoder lpcDecoder = new SilkLpcDecoder();
    private final SilkExcitation excitation = new SilkExcitation();
    private final SilkSynthesis synthesis = new SilkSynthesis();
    private final SilkStereoMixer stereoMixer = new SilkStereoMixer();
    private int previousCodedChannels;

    /**
     * @param outputChannels how many channels the stream is output as, 1 or 2, which may differ from how a packet
     *     codes it
     */
    SilkDecoder(final int outputChannels) {
        this.outputChannels = outputChannels;
    }

    /**
     * Decodes the SILK layer of one Opus frame: its LP-layer header bits, its LBRR (in-band redundancy) frames, which
     * are decoded and then dropped, as FFmpeg does, and its 1 to 3 SILK frames of 10 to 20 ms, which make the frame of
     * 10, 20, 40 or 60 ms.
     *
     * <p>The decoder reads from the range decoder {@code rangeDecoder} at its start, which is a fresh frame, and leaves
     * it where the SILK layer ends, so that for a hybrid frame the redundancy flag, then CELT, read on from there. It
     * never calls {@link RangeDecoder#shrink} or {@link RangeDecoder#initialise}.
     *
     * <p>It writes the frame's samples ({@code frameMillis} times the SILK rate of the bandwidth, 8, 12 or 16 kHz, per
     * second) into each of {@code output[0]} to {@code output[outputChannels - 1]}, starting at index 0. Each is in the
     * range -1 to 1, as FFmpeg's float samples are, before the resampling to 48 kHz. When the packet codes the stream
     * as stereo and the stream is output as stereo, {@code output[0]} and {@code output[1]} are the left and right
     * channels (after the mid/side unmixing). When it is coded as mono and output as stereo, both channels are the same
     * samples. When it is coded as stereo and output as mono, {@code output[0]} is the mid channel as it comes from the
     * decoder, without the side channel's prediction added, which is what FFmpeg outputs.
     *
     * @param rangeDecoder the frame's range decoder, positioned at its start
     * @param bandwidth narrowband, mediumband or wideband: the bandwidth {@link OpusBandwidth#silkBandwidth} gives
     * @param coding whether the frame codes one channel or two
     * @param frameMillis how long the Opus frame is, 10, 20, 40 or 60
     * @param output the buffers to write to, one per output channel, each at least {@link #MAX_FRAME_SAMPLES} long
     * @return how many samples were written to each channel
     * @throws InvalidFormatException when the frame is invalid: FFmpeg then zeroes the Opus frame
     */
    int decodeFrame(
            final RangeDecoder rangeDecoder,
            final OpusBandwidth bandwidth,
            final ChannelCoding coding,
            final int frameMillis,
            final float[][] output)
            throws InvalidFormatException {
        if (bandwidth.ordinal() > OpusBandwidth.WIDEBAND.ordinal() || frameMillis > MAX_FRAME_MILLIS) {
            throw new InvalidFormatException("Invalid parameters for the SILK decoder");
        }
        final var layout = new SilkLayout(bandwidth, frameMillis);
        final var codedChannels = coding.channelCount();
        startSideChannelWhenStereoBegins(codedChannels);
        final var header = readHeader(rangeDecoder, codedChannels, layout.frameCount(), frameMillis);
        decodeRedundantFrames(rangeDecoder, header, codedChannels, layout);
        for (var frame = 0; frame < layout.frameCount(); frame++) {
            decodeFrameChannels(rangeDecoder, header, codedChannels, layout, frame, output);
        }
        return layout.frameCount() * layout.frameLength();
    }

    /**
     * Forgets all state, as FFmpeg does when a frame doesn't use SILK (a CELT-only frame), so that a later SILK frame
     * starts afresh.
     */
    void reset() {
        channels[0].reset();
        channels[1].reset();
        stereoMixer.reset();
    }

    // The side channel starts empty when the stream goes from mono to stereo.
    private void startSideChannelWhenStereoBegins(final int codedChannels) {
        if (codedChannels > previousCodedChannels) {
            channels[1].reset();
        }
        previousCodedChannels = codedChannels;
    }

    private SilkHeader readHeader(
            final RangeDecoder rangeDecoder, final int codedChannels, final int frameCount, final int frameMillis) {
        final var activity = new int[codedChannels];
        final var redundancy = new int[codedChannels];
        for (var channel = 0; channel < codedChannels; channel++) {
            activity[channel] = readActivityFlags(rangeDecoder, frameCount);
            if (rangeDecoder.decodeBit(1)) {
                redundancy[channel] = 1;
            }
        }
        for (var channel = 0; channel < codedChannels; channel++) {
            if (redundancy[channel] != 0 && frameMillis > LONGEST_FRAME_MILLIS) {
                redundancy[channel] = readRedundancyFlags(rangeDecoder, frameMillis);
            }
        }
        return new SilkHeader(activity, redundancy);
    }

    // The voice activity flag of each of the frames, as a bit mask with the first frame's in the lowest bit.
    private int readActivityFlags(final RangeDecoder rangeDecoder, final int frameCount) {
        var flags = 0;
        for (var frame = 0; frame < frameCount; frame++) {
            if (rangeDecoder.decodeBit(1)) {
                flags |= 1 << frame;
            }
        }
        return flags;
    }

    // A packet of 40 or 60 ms says which of its frames have a redundancy copy, as a bit mask from 1: its model has no
    // 0, as at least one has is already known, so the models start from the symbol 1.
    private int readRedundancyFlags(final RangeDecoder rangeDecoder, final int frameMillis) {
        if (frameMillis == TWO_FRAME_MILLIS) {
            return rangeDecoder.decodeIcdf(SilkModels.LBRR_FLAGS_40, MODEL_BITS) + 1;
        }
        return rangeDecoder.decodeIcdf(SilkModels.LBRR_FLAGS_60, MODEL_BITS) + 1;
    }

    // The redundancy copies are read and dropped, but they leave their mark on what the real frames are decoded
    // from: the gain, filter and pitch lag of the last, and the stereo weights.
    private void decodeRedundantFrames(
            final RangeDecoder rangeDecoder,
            final SilkHeader header,
            final int codedChannels,
            final SilkLayout layout) {
        for (var frame = 0; frame < layout.frameCount(); frame++) {
            for (var channel = 0; channel < codedChannels; channel++) {
                if (header.hasRedundancy(channel, frame)) {
                    decodeRedundantFrame(rangeDecoder, header, codedChannels, layout, frame, channel);
                }
            }
        }
    }

    private void decodeRedundantFrame(
            final RangeDecoder rangeDecoder,
            final SilkHeader header,
            final int codedChannels,
            final SilkLayout layout,
            final int frame,
            final int channel) {
        if (codedChannels == 2 && channel == 0) {
            readStereoPrediction(rangeDecoder, redundantSideActivity(header, frame));
        }
        readFrame(rangeDecoder, layout, channels[channel], frame, SilkActivity.ACTIVE);
    }

    // A redundancy copy of the mid channel codes a mid-only flag unless the side channel has a copy too.
    private SilkActivity redundantSideActivity(final SilkHeader header, final int frame) {
        if (header.hasRedundancy(1, frame)) {
            return SilkActivity.ACTIVE;
        }
        return SilkActivity.INACTIVE;
    }

    private void decodeFrameChannels(
            final RangeDecoder rangeDecoder,
            final SilkHeader header,
            final int codedChannels,
            final SilkLayout layout,
            final int frame,
            final float[][] output) {
        var sideChannel = SilkSideChannel.CODED;
        for (var channel = 0; channel < codedChannels && sideChannel == SilkSideChannel.CODED; channel++) {
            if (codedChannels == 2 && channel == 0) {
                sideChannel = readStereoPrediction(rangeDecoder, header.activity(1, frame));
            }
            decodeChannelFrame(rangeDecoder, layout, channel, frame, header.activity(channel, frame));
        }
        if (sideChannel == SilkSideChannel.MID_ONLY) {
            channels[1].reset();
        }
        writeOutput(codedChannels, layout, frame, output);
    }

    private void decodeChannelFrame(
            final RangeDecoder rangeDecoder,
            final SilkLayout layout,
            final int channel,
            final int frame,
            final SilkActivity activity) {
        final var state = channels[channel];
        final var sideInfo = readFrame(rangeDecoder, layout, state, frame, activity);
        // A side channel that isn't output is read for the sake of the bits after it, and not synthesised.
        if (channel != outputChannels) {
            synthesis.synthesise(state, layout, sideInfo, excitation.samples());
        }
    }

    // The mid channel's output as it is, or the stereo channels unmixed.
    private void writeOutput(
            final int codedChannels, final SilkLayout layout, final int frame, final float[][] output) {
        final var frameLength = layout.frameLength();
        final var outputStart = frame * frameLength;
        if (codedChannels == 1 || outputChannels == 1) {
            final var start = SilkChannelState.HISTORY - frameLength - OUTPUT_DELAY;
            for (var channel = 0; channel < outputChannels; channel++) {
                System.arraycopy(channels[0].output(), start, output[channel], outputStart, frameLength);
            }
        } else {
            stereoMixer.unmix(channels[0], channels[1], output[0], output[1], outputStart, layout);
        }
    }

    // Reads the weights that predict the side channel from the mid, as two numbers each of a coarse index into a table
    // and a fine step, and whether the side channel is coded at all, which only a frame whose side channel is inactive
    // says.
    private SilkSideChannel readStereoPrediction(final RangeDecoder rangeDecoder, final SilkActivity sideActivity) {
        final var combination = rangeDecoder.decodeIcdf(SilkModels.STEREO_S1, MODEL_BITS);
        final var firstIndex = rangeDecoder.decodeIcdf(SilkModels.STEREO_S2, MODEL_BITS)
                + STEREO_WEIGHT_GROUP * (combination / STEREO_WEIGHT_STEPS);
        final var firstStep = rangeDecoder.decodeIcdf(SilkModels.STEREO_S3, MODEL_BITS);
        final var secondIndex = rangeDecoder.decodeIcdf(SilkModels.STEREO_S2, MODEL_BITS)
                + STEREO_WEIGHT_GROUP * (combination % STEREO_WEIGHT_STEPS);
        final var secondStep = rangeDecoder.decodeIcdf(SilkModels.STEREO_S3, MODEL_BITS);
        final var first = stereoWeight(firstIndex, firstStep);
        final var second = stereoWeight(secondIndex, secondStep);
        stereoMixer.setWeights((float) ((first - second) / STEREO_WEIGHT_UNIT), (float) (second / STEREO_WEIGHT_UNIT));
        return switch (sideActivity) {
            case ACTIVE -> SilkSideChannel.CODED;
            case INACTIVE -> readMidOnlyFlag(rangeDecoder);
        };
    }

    private SilkSideChannel readMidOnlyFlag(final RangeDecoder rangeDecoder) {
        if (rangeDecoder.decodeIcdf(SilkModels.MID_ONLY, MODEL_BITS) == 0) {
            return SilkSideChannel.CODED;
        }
        return SilkSideChannel.MID_ONLY;
    }

    // A weight in Q13: the table's entry for the index, and the step of five tenths, in odd tenths, towards the next
    // entry.
    private int stereoWeight(final int index, final int step) {
        final var base = SilkTables.STEREO_WEIGHTS[index];
        final var next = SilkTables.STEREO_WEIGHTS[index + 1];
        return base + (((next - base) * STEREO_WEIGHT_FRACTION) >> 16) * (step * 2 + 1);
    }

    // Reads a frame's parameters and excitation, which the excitation buffer keeps until the next frame is read.
    private SilkSideInfo readFrame(
            final RangeDecoder rangeDecoder,
            final SilkLayout layout,
            final SilkChannelState state,
            final int frame,
            final SilkActivity activity) {
        final var frameType = readFrameType(rangeDecoder, activity);
        final var signalType = frameType.signalType();
        final var gains = readGains(rangeDecoder, layout, state, frame, signalType);
        final var lpc = lpcDecoder.decode(rangeDecoder, layout, state, signalType);
        final var sideInfo = switch (signalType) {
            case VOICED -> readVoicedInfo(rangeDecoder, layout, state, frame, frameType, gains, lpc);
            case INACTIVE, UNVOICED -> unvoicedInfo(layout, frameType, gains, lpc);
        };
        excitation.decode(rangeDecoder, layout, signalType, frameType.offset());
        return sideInfo;
    }

    private FrameType readFrameType(final RangeDecoder rangeDecoder, final SilkActivity activity) {
        return switch (activity) {
            case INACTIVE -> {
                final var offset = rangeDecoder.decodeIcdf(SilkModels.FRAME_TYPE_INACTIVE, MODEL_BITS);
                yield new FrameType(SilkSignalType.INACTIVE, QuantizationOffset.values()[offset]);
            }
            case ACTIVE -> {
                final var type = rangeDecoder.decodeIcdf(SilkModels.FRAME_TYPE_ACTIVE, MODEL_BITS);
                final var offset = QuantizationOffset.values()[type & 1];
                if (type >> 1 == 0) {
                    yield new FrameType(SilkSignalType.UNVOICED, offset);
                }
                yield new FrameType(SilkSignalType.VOICED, offset);
            }
        };
    }

    private float[] readGains(
            final RangeDecoder rangeDecoder,
            final SilkLayout layout,
            final SilkChannelState state,
            final int frame,
            final SilkSignalType signalType) {
        final var gains = new float[layout.subframes()];
        for (var subframe = 0; subframe < gains.length; subframe++) {
            final int logGain;
            if (subframe == 0 && (frame == 0 || state.history() == SilkHistory.EMPTY)) {
                logGain = readAbsoluteGain(rangeDecoder, state, signalType);
            } else {
                logGain = readRelativeGain(rangeDecoder, state);
            }
            state.setLogGain(logGain);
            gains[subframe] = linearGain(logGain);
        }
        return gains;
    }

    // The gain in 1/6 dB steps, in 3 high bits and 3 low bits. After a decoded frame it is not allowed to fall more
    // than
    // 16 steps below that frame's last.
    private int readAbsoluteGain(
            final RangeDecoder rangeDecoder, final SilkChannelState state, final SilkSignalType signalType) {
        final var highBits = rangeDecoder.decodeIcdf(SilkModels.GAIN_HIGHBITS[signalType.typeIndex()], MODEL_BITS);
        final var logGain = (highBits << GAIN_LOW_BITS) | rangeDecoder.decodeIcdf(SilkModels.GAIN_LOWBITS, MODEL_BITS);
        if (state.history() == SilkHistory.DECODED) {
            return Math.max(logGain, state.logGain() - ABSOLUTE_GAIN_FLOOR);
        }
        return logGain;
    }

    // The gain as a change from the previous subframe's.
    private int readRelativeGain(final RangeDecoder rangeDecoder, final SilkChannelState state) {
        final var delta = rangeDecoder.decodeIcdf(SilkModels.GAIN_DELTA, MODEL_BITS);
        final var gain = Math.max((delta << 1) - GAIN_DELTA_FLOOR, state.logGain() + delta - GAIN_DELTA_BASE);
        return Math.clamp(gain, 0, MAX_LOG_GAIN);
    }

    // 2 to the power of the log gain over 128 (a gain of 6 dB steps in Q7), approximated by a piecewise linear
    // interpolation between its integer powers, and made a float of Q16.
    private float linearGain(final int logGain) {
        final var scaled = (logGain * LOG_GAIN_SCALE >> 16) + LOG_GAIN_OFFSET;
        final var integerPart = scaled >> 7;
        final var fraction = scaled & 127;
        final var power = 1 << integerPart;
        final var linear = power + ((-174 * fraction * (128 - fraction) >> 16) + fraction) * (power >> 7);
        return linear / LINEAR_GAIN_UNIT;
    }

    private SilkSideInfo unvoicedInfo(
            final SilkLayout layout, final FrameType frameType, final float[] gains, final SilkLpc lpc) {
        return new SilkSideInfo(
                frameType.signalType(),
                gains,
                lpc,
                new int[layout.subframes()],
                new float[layout.subframes()][LTP_TAPS],
                DEFAULT_LTP_SCALE);
    }

    private SilkSideInfo readVoicedInfo(
            final RangeDecoder rangeDecoder,
            final SilkLayout layout,
            final SilkChannelState state,
            final int frame,
            final FrameType frameType,
            final float[] gains,
            final SilkLpc lpc) {
        final var primaryLag = readPrimaryLag(rangeDecoder, layout, state, frame);
        state.setPrimaryLag(primaryLag);
        final var pitchLags = readPitchLags(rangeDecoder, layout, primaryLag);
        final var ltpTaps = readLtpTaps(rangeDecoder, layout);
        final var ltpScale = readLtpScale(rangeDecoder, frame);
        return new SilkSideInfo(frameType.signalType(), gains, lpc, pitchLags, ltpTaps, ltpScale);
    }

    // The primary pitch lag, as a change from the last frame's if that was voiced and this isn't the first frame of the
    // packet, unless the change is the one that says to code it afresh.
    private int readPrimaryLag(
            final RangeDecoder rangeDecoder, final SilkLayout layout, final SilkChannelState state, final int frame) {
        if (frame != 0 && state.previousSignalType() == SilkSignalType.VOICED) {
            final var delta = rangeDecoder.decodeIcdf(SilkModels.PITCH_DELTA, MODEL_BITS);
            if (delta != 0) {
                return state.primaryLag() + delta - PITCH_DELTA_BIAS;
            }
        }
        return readAbsoluteLag(rangeDecoder, layout.bandwidth());
    }

    private int readAbsoluteLag(final RangeDecoder rangeDecoder, final OpusBandwidth bandwidth) {
        final var highBits = rangeDecoder.decodeIcdf(SilkModels.PITCH_HIGHBITS, MODEL_BITS);
        final var lowBits = rangeDecoder.decodeIcdf(lowBitsModel(bandwidth), MODEL_BITS);
        final var index = bandwidth.ordinal();
        return SilkTables.PITCH_MIN_LAG[index] + highBits * SilkTables.PITCH_SCALE[index] + lowBits;
    }

    // The low bits of the lag take one of three models, which are shared with other symbols that have as many values.
    private int[] lowBitsModel(final OpusBandwidth bandwidth) {
        return switch (bandwidth) {
            case NARROWBAND -> SilkModels.LCG_SEED;
            case MEDIUMBAND -> SilkModels.PITCH_LOWBITS_MB;
            case WIDEBAND, SUPERWIDEBAND, FULLBAND -> SilkModels.GAIN_LOWBITS;
        };
    }

    // The lag of each subframe: the primary lag moved by a contour, an offset for each subframe, which is one of a few
    // the bandwidth and the length of the frame have.
    private int[] readPitchLags(final RangeDecoder rangeDecoder, final SilkLayout layout, final int primaryLag) {
        final var offsets = readContour(rangeDecoder, layout);
        final var index = layout.bandwidth().ordinal();
        final var lags = new int[layout.subframes()];
        for (var subframe = 0; subframe < lags.length; subframe++) {
            lags[subframe] = Math.clamp(
                    primaryLag + offsets[subframe], SilkTables.PITCH_MIN_LAG[index], SilkTables.PITCH_MAX_LAG[index]);
        }
        return lags;
    }

    private int[] readContour(final RangeDecoder rangeDecoder, final SilkLayout layout) {
        final var narrowband = layout.bandwidth() == OpusBandwidth.NARROWBAND;
        if (layout.subframes() == 2) {
            if (narrowband) {
                return SilkTables.PITCH_OFFSET_NB10MS[
                        rangeDecoder.decodeIcdf(SilkModels.PITCH_CONTOUR_NB10MS, MODEL_BITS)];
            }
            return SilkTables.PITCH_OFFSET_MBWB10MS[
                    rangeDecoder.decodeIcdf(SilkModels.PITCH_CONTOUR_MBWB10MS, MODEL_BITS)];
        }
        if (narrowband) {
            return SilkTables.PITCH_OFFSET_NB20MS[rangeDecoder.decodeIcdf(SilkModels.PITCH_CONTOUR_NB20MS, MODEL_BITS)];
        }
        return SilkTables.PITCH_OFFSET_MBWB20MS[rangeDecoder.decodeIcdf(SilkModels.PITCH_CONTOUR_MBWB20MS, MODEL_BITS)];
    }

    // The five taps of each subframe's long-term filter: one of three codebooks for the frame, then an entry of it for
    // each subframe.
    private float[][] readLtpTaps(final RangeDecoder rangeDecoder, final SilkLayout layout) {
        final var codebook = rangeDecoder.decodeIcdf(SilkModels.LTP_FILTER, MODEL_BITS);
        final var taps = new float[layout.subframes()][LTP_TAPS];
        for (var subframe = 0; subframe < taps.length; subframe++) {
            final var entry = readLtpTapsEntry(rangeDecoder, codebook);
            for (var tap = 0; tap < LTP_TAPS; tap++) {
                taps[subframe][tap] = entry[tap] / LTP_TAP_UNIT;
            }
        }
        return taps;
    }

    private int[] readLtpTapsEntry(final RangeDecoder rangeDecoder, final int codebook) {
        return switch (codebook) {
            case 0 -> SilkTables.LTP_FILTER0_TAPS[rangeDecoder.decodeIcdf(SilkModels.LTP_FILTER0_SEL, MODEL_BITS)];
            case 1 -> SilkTables.LTP_FILTER1_TAPS[rangeDecoder.decodeIcdf(SilkModels.LTP_FILTER1_SEL, MODEL_BITS)];
            default -> SilkTables.LTP_FILTER2_TAPS[rangeDecoder.decodeIcdf(SilkModels.LTP_FILTER2_SEL, MODEL_BITS)];
        };
    }

    // The scale of the long-term prediction of the first subframes, which only the first frame of a packet codes.
    private float readLtpScale(final RangeDecoder rangeDecoder, final int frame) {
        if (frame == 0) {
            final var index = rangeDecoder.decodeIcdf(SilkModels.LTP_SCALE_INDEX, MODEL_BITS);
            return SilkTables.LTP_SCALE_FACTOR[index] / LTP_SCALE_UNIT;
        }
        return DEFAULT_LTP_SCALE;
    }
}
