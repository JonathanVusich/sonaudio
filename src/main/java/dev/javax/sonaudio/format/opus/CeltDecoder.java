package dev.javax.sonaudio.format.opus;

import dev.javax.sonaudio.exception.InvalidFormatException;
import java.util.Arrays;

/**
 * The CELT layer of one coded stream (RFC 6716 section 4.3), which decodes a frame's CELT part to samples at 48 kHz.
 * Everything {@link OpusStream} does around it (the range decoder, the redundancy frames, the hybrid mix, the delay
 * that SILK's resampling puts on a hybrid frame, the mode switches) is written against the contract below.
 *
 * <p>FFmpeg's equivalent is {@code ff_celt_decode_frame} with {@code ff_celt_flush} ({@code dec_celt.c}), which
 * {@code dec.c} calls for each CELT-only or hybrid Opus frame, and also for the 5 ms redundancy frames. This decoder
 * gives the samples FFmpeg's float decoder gives: it applies the same arithmetic in the same order, in the same
 * precision, but does not conceal lost packets, which FFmpeg's does not either.
 *
 * <p>State kept from frame to frame, which {@link #reset} forgets: for each channel the band energies of the last
 * frame and the one before it (the prediction of the next frame's coarse energy), the 120 samples of overlap from the
 * last frame's inverse MDCT, the post-filter's history and the previous frame's post-filter parameters, the
 * de-emphasis filter's memory, and the random generator's seed, which comes from the range decoder's state after each
 * frame.
 */
final class CeltDecoder {

    /**
     * The most samples per channel a frame writes: 20 ms at 48 kHz.
     */
    static final int MAX_FRAME_SAMPLES = 960;
    /**
     * The first band that a hybrid frame's CELT layer codes: the bands below it are SILK's.
     */
    static final int HYBRID_START_BAND = 17;

    private static final int SILENCE_PROBABILITY_BITS = 15;
    private static final int POSTFILTER_PROBABILITY_BITS = 1;
    private static final int TRANSIENT_PROBABILITY_BITS = 3;
    private static final int POSTFILTER_MIN_BITS = 16;
    private static final int MIN_POSTFILTER_PERIOD = 15;
    private static final float POSTFILTER_GAIN_STEP = 0.09375f;

    private final int outputChannels;
    private final CeltNoise noise = new CeltNoise();
    private final CeltEnergy energy = new CeltEnergy();
    private final CeltTimeFrequency timeFrequency = new CeltTimeFrequency();
    private final CeltBitAllocator allocator = new CeltBitAllocator();
    private final CeltBandDecoder bands = new CeltBandDecoder(noise);
    private final CeltAntiCollapse antiCollapse = new CeltAntiCollapse(noise, energy);
    private final CeltChannelSynthesis[] synthesis = {new CeltChannelSynthesis(), new CeltChannelSynthesis()};

    /**
     * @param outputChannels how many channels the stream is output as, 1 or 2, which may differ from how a packet
     *     codes it
     */
    CeltDecoder(final int outputChannels) {
        this.outputChannels = outputChannels;
    }

    /**
     * Decodes the CELT layer of one Opus frame, or of one 5 ms redundancy frame, reading from the range decoder where
     * it is, which for a hybrid frame is where SILK's layer and the redundancy flags ended. The frame's raw bits are
     * read from the end of the frame: {@link RangeDecoder#frameBits} is its size, and {@link RangeDecoder#shrink} has
     * already cut a redundancy frame off it.
     *
     * <p>It writes {@code frameSamples} samples into each of {@code output[0]} to {@code output[outputChannels - 1]},
     * starting at index 0, at 48 kHz, each in the range -1 to 1 as FFmpeg's float samples are, after the inverse MDCT,
     * the overlap with the last frame, the post-filter and the de-emphasis. When the stream is coded as stereo and
     * output as mono, FFmpeg adds the two channels' coefficients and halves the result after the inverse MDCT, which
     * is the average of the channels; when coded as mono and output as stereo, both channels are the same samples.
     *
     * @param rangeDecoder the frame's range decoder
     * @param coding whether the frame codes one channel or two
     * @param frameSamples how many samples the frame has per channel: 120, 240, 480 or 960 (2.5, 5, 10 or 20 ms)
     * @param startBand the first band the frame codes: 0, or {@link #HYBRID_START_BAND} for a hybrid frame
     * @param endBand the band to stop before, which {@link OpusBandwidth#celtEndBand} gives
     * @param output the buffers to write to, one per output channel, each at least {@link #MAX_FRAME_SAMPLES} long
     * @throws InvalidFormatException when the arguments or frame are invalid: FFmpeg then zeroes the Opus frame
     */
    void decodeFrame(
            final RangeDecoder rangeDecoder,
            final ChannelCoding coding,
            final int frameSamples,
            final int startBand,
            final int endBand,
            final float[][] output)
            throws InvalidFormatException {
        final var sizeShift = sizeShift(frameSamples);
        if (startBand < 0 || startBand > endBand || endBand > CeltTables.BAND_COUNT) {
            throw new InvalidFormatException("Invalid CELT band range");
        }
        final var frameBits = rangeDecoder.frameBits();
        final var tellBeforeSilence = rangeDecoder.tell();
        final var silence = decodeSilence(rangeDecoder, frameBits, tellBeforeSilence);
        // FFmpeg checks what the header parts have room for against the bits used before the silence flag, until a
        // part that reads something updates it.
        var consumed = tellBeforeSilence;
        if (silence == Silence.SILENT) {
            consumed = frameBits;
        }
        var postfilter = CeltPostfilterParameters.none();
        if (startBand == 0 && consumed + POSTFILTER_MIN_BITS <= frameBits) {
            postfilter = decodePostfilter(rangeDecoder, frameBits);
            consumed = rangeDecoder.tell();
        }
        final var blockMode = decodeBlockMode(rangeDecoder, sizeShift, frameBits, consumed);
        final var frame = new CeltFrame(sizeShift, coding.channelCount(), startBand, endBand, frameBits, blockMode);
        System.out.println("DBG size=" + sizeShift + " silence=" + silence + " mode=" + blockMode + " pf=" + postfilter.period() + "," + java.util.Arrays.toString(postfilter.gains()) + " bits=" + frameBits + " tell=" + rangeDecoder.tell());
        decodeSpectrum(rangeDecoder, frame);
        System.out.println("DBG after spectrum tell=" + rangeDecoder.tell());
        { final var sb = new StringBuilder("DBG energies:"); for (var b = 0; b < 21; b++) { sb.append(String.format(" %.2f", energy.energy(0, b))); } System.out.println(sb); }
        synthesizeFrame(frame, silence, postfilter, output);
        energy.finishFrame(frame, frame.channels());
        noise.seed(rangeDecoder.range());
    }

    private int sizeShift(final int frameSamples) throws InvalidFormatException {
        for (var shift = 0; shift < 4; shift++) {
            if (frameSamples == CeltWindowTables.LENGTH << shift) {
                return shift;
            }
        }
        throw new InvalidFormatException("Invalid CELT frame size: " + frameSamples);
    }

    private Silence decodeSilence(final RangeDecoder rangeDecoder, final int frameBits, final int consumed) {
        var silent = consumed >= frameBits;
        if (!silent && consumed == 1) {
            silent = rangeDecoder.decodeBit(SILENCE_PROBABILITY_BITS);
        }
        if (silent) {
            rangeDecoder.consumeToEnd();
            return Silence.SILENT;
        }
        return Silence.CODED;
    }

    private CeltPostfilterParameters decodePostfilter(final RangeDecoder rangeDecoder, final int frameBits) {
        if (!rangeDecoder.decodeBit(POSTFILTER_PROBABILITY_BITS)) {
            return CeltPostfilterParameters.none();
        }
        final var octave = rangeDecoder.decodeUniform(6);
        final var period = (16 << octave) + rangeDecoder.decodeRawBits(4 + octave) - 1;
        final var gain = POSTFILTER_GAIN_STEP * (rangeDecoder.decodeRawBits(3) + 1);
        var tapset = 0;
        if (rangeDecoder.tell() + 2 <= frameBits) {
            tapset = rangeDecoder.decodeIcdf(CeltTables.TAPSET_ICDF, CeltTables.TAPSET_BITS);
        }
        final var taps = CeltTables.POSTFILTER_TAPS[tapset];
        final var gains = new float[] {gain * taps[0], gain * taps[1], gain * taps[2]};
        return new CeltPostfilterParameters(Math.max(period, MIN_POSTFILTER_PERIOD), gains);
    }

    private BlockMode decodeBlockMode(
            final RangeDecoder rangeDecoder, final int sizeShift, final int frameBits, final int consumed) {
        if (sizeShift != 0
                && consumed + TRANSIENT_PROBABILITY_BITS <= frameBits
                && rangeDecoder.decodeBit(TRANSIENT_PROBABILITY_BITS)) {
            return BlockMode.SHORT;
        }
        return BlockMode.LONG;
    }

    // Decodes everything that follows the frame's header: energies, bit allocation, shapes, anti-collapse and
    // denormalisation, which leave each channel's coefficients ready for the inverse transform.
    private void decodeSpectrum(final RangeDecoder rangeDecoder, final CeltFrame frame) {
        if (frame.channels() == 1) {
            energy.mergeChannelsForMono();
        }
        energy.decodeCoarse(rangeDecoder, frame);
        timeFrequency.decode(rangeDecoder, frame);
        final var allocation = allocator.allocate(rangeDecoder, frame);
        { final var sb = new StringBuilder("DBG alloc coded=" + allocation.codedBands() + " spread=" + allocation.spread() + " rem=" + allocation.remainingBits() + " ac=" + allocation.anticollapseReserve() + " tf=" + java.util.Arrays.toString(timeFrequency.changes()) + " pulses:"); for (var b = 0; b < 21; b++) { sb.append(" " + allocator.pulses(b) + "/" + allocator.fineBits(b)); } System.out.println(sb); }
        energy.decodeFine(rangeDecoder, frame, allocator);
        bands.decodeBands(rangeDecoder, frame, allocation, allocator, timeFrequency.changes());
        var collapsed = false;
        if (allocation.anticollapseReserve() != 0) {
            collapsed = rangeDecoder.decodeRawBits(1) != 0;
            System.out.println("DBG collapsed=" + collapsed);
        }
        energy.decodeFinal(rangeDecoder, frame, allocator);
        for (var channel = 0; channel < frame.channels(); channel++) {
            if (collapsed && System.getenv("NOAC") == null) {
                antiCollapse.fillCollapsedBlocks(channel, frame, allocator, bands, bands.coefficients(channel));
            }
            energy.denormalize(channel, frame, bands.coefficients(channel));
        }
    }

    private void synthesizeFrame(
            final CeltFrame frame,
            final Silence silence,
            final CeltPostfilterParameters postfilter,
            final float[][] output) {
        var downmix = DownmixMode.NONE;
        if (outputChannels < frame.channels()) {
            addSecondChannel(frame);
            downmix = DownmixMode.AVERAGE;
        }
        if (silence == Silence.SILENT) {
            energy.setSilent();
            Arrays.fill(bands.coefficients(0), 0f);
            Arrays.fill(bands.coefficients(1), 0f);
        }
        for (var channel = 0; channel < outputChannels; channel++) {
            final var coefficients = coefficientsOf(frame, channel);
            synthesis[channel].synthesize(frame, coefficients, downmix, postfilter, output[channel]);
        }
    }

    private void addSecondChannel(final CeltFrame frame) {
        final var first = bands.coefficients(0);
        final var second = bands.coefficients(1);
        for (var index = 0; index < frame.frameSamples(); index++) {
            first[index] += second[index];
        }
    }

    // A mono frame that is output as stereo gives both channels the same coefficients.
    private float[] coefficientsOf(final CeltFrame frame, final int channel) {
        if (channel >= frame.channels()) {
            return bands.coefficients(0);
        }
        return bands.coefficients(channel);
    }

    /**
     * Forgets all state, as FFmpeg does when a frame doesn't use CELT (a SILK-only frame), and before a redundancy
     * frame that is decoded in the middle of a stream, so that a later CELT frame starts afresh.
     */
    void reset() {
        energy.reset();
        noise.reset();
        synthesis[0].reset();
        synthesis[1].reset();
    }

    private enum Silence {
        SILENT,
        CODED
    }
}
