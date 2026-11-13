package dev.javax.sonaudio.format.opus;

import dev.javax.sonaudio.exception.InvalidFormatException;
import java.util.Arrays;

/**
 * The CELT layer of one coded stream (RFC 6716 section 4.3), which decodes a frame's CELT part to samples at 48 kHz.
 * This is a plug point of the Opus decoder: it is a STUB, which writes silence of the right length and reads nothing
 * from the range decoder, until the CELT decoding is written in its place. Everything {@link OpusStream} does around
 * it (the range decoder, the redundancy frames, the hybrid mix, the delay that SILK's resampling puts on a hybrid frame,
 * the mode switches) is already written against the contract below.
 *
 * <p>FFmpeg's equivalent is {@code ff_celt_decode_frame} with {@code ff_celt_flush} ({@code dec_celt.c}), which
 * {@code dec.c} calls for each CELT-only or hybrid Opus frame, and also for the 5 ms redundancy frames.
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

    private final int outputChannels;

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
        if (startBand < 0 || startBand > endBand || frameSamples > MAX_FRAME_SAMPLES) {
            throw new InvalidFormatException("Invalid CELT frame parameters");
        }
        for (var channel = 0; channel < outputChannels; channel++) {
            Arrays.fill(output[channel], 0, frameSamples, 0f);
        }
    }

    /**
     * Forgets all state, as FFmpeg does when a frame doesn't use CELT (a SILK-only frame), and before a redundancy
     * frame that is decoded in the middle of a stream, so that a later CELT frame starts afresh.
     */
    void reset() {}
}
