package dev.javax.sonaudio.format.opus;

import dev.javax.sonaudio.exception.InvalidFormatException;
import java.util.Arrays;

/**
 * The SILK layer of one coded stream (RFC 6716 section 4.2), which decodes a frame's SILK part to samples at SILK's own
 * rate. This is a plug point of the Opus decoder: it is a STUB, which writes silence of the right length and reads
 * nothing from the range decoder, until the SILK decoding is written in its place. Everything {@link OpusStream}
 * does around it (the range decoder, the redundancy frames, the resampling, the hybrid mix, the mode switches) is
 * already written against the contract below.
 *
 * <p>FFmpeg's equivalent is {@code ff_silk_decode_superframe} with {@code ff_silk_flush} ({@code silk.c}), which
 * {@code dec.c} drives once for each Opus frame of a SILK-only or hybrid packet.
 *
 * <p>State kept from frame to frame, which {@link #reset} forgets: for each of the two coded channels the last frame's
 * decoded history (the output and LPC state, the pitch lag, gains and the stereo prediction weights of the previous
 * frame), and which channels were coded in the frame before, so a stream that goes from mono to stereo starts the side
 * channel from nothing.
 */
final class SilkDecoder {

    /**
     * The most samples per channel a frame writes: 60 ms at 16 kHz.
     */
    static final int MAX_FRAME_SAMPLES = 960;

    private final int outputChannels;

    /**
     * @param outputChannels how many channels the stream is output as, 1 or 2, which may differ from how a packet
     *     codes it
     */
    SilkDecoder(final int outputChannels) {
        this.outputChannels = outputChannels;
    }

    /**
     * Decodes the SILK layer of one Opus frame: its LP-layer header bits, its LBRR (in-band redundancy) frames, which
     * are decoded and then dropped, as FFmpeg does, and its 1 to 3 SILK frames of 10 to 20 ms, which make the frame of 10,
     * 20, 40 or 60 ms.
     *
     * <p>The decoder reads from the range decoder {@code rangeDecoder} at its start, which is a fresh frame, and leaves
     * it where the SILK layer ends, so that for a hybrid frame the redundancy flag, then CELT, read on from there. It
     * must not call {@link RangeDecoder#shrink} or {@link RangeDecoder#initialise}.
     *
     * <p>It writes the frame's samples ({@code frameMillis} times the SILK rate of the bandwidth, 8, 12 or 16 kHz, per
     * second) into each of {@code output[0]} to {@code output[outputChannels - 1]}, starting at index 0. Each is in the
     * range -1 to 1, as
     * FFmpeg's float samples are, before the resampling to 48 kHz. When the packet codes the stream as stereo and the
     * stream is output as stereo, {@code output[0]} and {@code output[1]} are the left and right channels (after the
     * mid/side unmixing). When it is coded as mono and output as stereo, both channels are the same samples. When it is
     * coded as stereo and output as mono, {@code output[0]} is the mid channel as it comes from the decoder, without
     * the side channel's prediction added, which is what FFmpeg outputs.
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
        if (bandwidth.ordinal() > OpusBandwidth.WIDEBAND.ordinal() || frameMillis > 60) {
            throw new InvalidFormatException("Invalid parameters for the SILK decoder");
        }
        final var samples = frameMillis * bandwidth.silkSampleRate() / 1000;
        for (var channel = 0; channel < outputChannels; channel++) {
            Arrays.fill(output[channel], 0, samples, 0f);
        }
        return samples;
    }

    /**
     * Forgets all state, as FFmpeg does when a frame doesn't use SILK (a CELT-only frame), so that a later SILK frame
     * starts afresh.
     */
    void reset() {}
}
