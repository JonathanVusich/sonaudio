package dev.javax.sonaudio.format.opus;

import java.util.Arrays;

/**
 * Resamples the SILK layer's samples, at 8, 12 or 16 kHz, to 48 kHz, as FFmpeg's {@code dec.c} does with a libswresample
 * context for each coded stream ({@code filter_size} 16, linear interpolation off, the stream's channel layout). This
 * is a plug point of the Opus decoder, and a STUB: it only has the shape of FFmpeg's output (how many samples come out
 * of each call), and its samples are silence. It is not part of anyone's SILK or CELT work in the brief that made it,
 * so its owner is whoever finishes SILK first: the SILK decoder's output isn't right at 48 kHz without it.
 *
 * <p>What FFmpeg does with it, which {@link OpusStream} already does around this class. The context is opened on the
 * first SILK or hybrid frame, with {@code silk_resample_delay[bandwidth]} samples of silence at the input rate fed in
 * first, which are 4, 8, 11, 11 and 11 samples for narrowband to fullband. Every SILK frame is then converted with room
 * for the Opus frame's samples at 48 kHz. Because libswresample holds samples back, the first frame comes out short by a
 * number of samples, for narrowband 24 and for every other bandwidth measured, 0, and each frame after it is complete.
 * {@link OpusStream} counts the samples that are short as delayed, and has them flushed out, as silence at the end of
 * the resampler's input ({@link #flush}), when the stream switches to CELT, changes SILK's rate, or ends. The
 * context is closed after each flush, and opened again with its silence by the next SILK frame.
 *
 * <p>State kept between calls: the filter's history, for each output channel, which is what the silence and every
 * frame's samples were fed into, and the polyphase filter's phase. {@link #close} forgets all of it.
 */
final class SilkResampler {

    private static final int OUTPUT_SAMPLE_RATE = 48000;
    // How many of 48 kHz samples FFmpeg's resampler holds back, for each bandwidth, by what the Opus frame loses:
    // narrowband's 4 samples of silence at 8 kHz are 24 samples at 48 kHz. Measured on the FFmpeg 9.0.2 binary.
    private static final int NARROWBAND_LOSS = 24;

    private final int outputChannels;
    private ResamplerState state = ResamplerState.CLOSED;
    private int inputSampleRate;
    private int pendingLoss;

    /**
     * @param outputChannels how many channels are resampled, 1 or 2
     */
    SilkResampler(final int outputChannels) {
        this.outputChannels = outputChannels;
    }

    ResamplerState state() {
        return state;
    }

    /**
     * @return the rate of the samples the open resampler is converting: 8000, 12000 or 16000
     */
    int inputSampleRate() {
        return inputSampleRate;
    }

    /**
     * Opens the resampler for a rate, feeding it the silence that FFmpeg does first.
     *
     * @param bandwidth the bandwidth of the packet, which gives the input rate and the amount of silence
     */
    void open(final OpusBandwidth bandwidth) {
        state = ResamplerState.OPEN;
        inputSampleRate = bandwidth.silkSampleRate();
        pendingLoss = 0;
        if (bandwidth == OpusBandwidth.NARROWBAND) {
            pendingLoss = NARROWBAND_LOSS;
        }
    }

    /**
     * Converts a frame's samples, as {@code swr_convert} does. The stub's output is silence, and has the number of
     * samples that FFmpeg's has: all of the input's at 48 kHz, but for the first frame after {@link #open} that the
     * resampler holds back some of, and never more than the capacity.
     *
     * @param input the SILK layer's samples, one array per output channel
     * @param inputSamples how many samples there are in each, from index 0
     * @param output where to write the samples at 48 kHz, one array per output channel
     * @param outputOffset the index in each to start at
     * @param outputCapacity how many samples may be written to each
     * @return how many samples were written to each channel
     */
    int convert(
            final float[][] input,
            final int inputSamples,
            final float[][] output,
            final int outputOffset,
            final int outputCapacity) {
        final var produced = inputSamples * (OUTPUT_SAMPLE_RATE / inputSampleRate) - pendingLoss;
        pendingLoss = 0;
        final var written = Math.min(produced, outputCapacity);
        for (var channel = 0; channel < outputChannels; channel++) {
            Arrays.fill(output[channel], outputOffset, outputOffset + written, 0f);
        }
        return written;
    }

    /**
     * Writes the samples that the resampler held back, as {@code swr_convert} does when it is given no input and is
     * asked for as many as it owes.
     *
     * @param output where to write the samples at 48 kHz, one array per output channel
     * @param outputOffset the index in each to start at
     * @param samples how many samples to write to each, which is how many FFmpeg's decoder is short of the Opus
     *     frames' lengths so far
     */
    void flush(final float[][] output, final int outputOffset, final int samples) {
        for (var channel = 0; channel < outputChannels; channel++) {
            Arrays.fill(output[channel], outputOffset, outputOffset + samples, 0f);
        }
    }

    /**
     * Closes the resampler, forgetting its history, as {@code swr_close} does.
     */
    void close() {
        state = ResamplerState.CLOSED;
        pendingLoss = 0;
    }
}
