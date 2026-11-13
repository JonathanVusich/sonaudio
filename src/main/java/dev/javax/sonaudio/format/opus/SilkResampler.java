package dev.javax.sonaudio.format.opus;

import dev.javax.sonaudio.internal.ResamplerCursor;
import dev.javax.sonaudio.internal.ResamplerFilter;
import java.util.Arrays;

/**
 * Resamples the SILK layer's samples, at 8, 12 or 16 kHz, to 48 kHz, as FFmpeg's {@code dec.c} does with a libswresample
 * context for each coded stream: {@code filter_size} 16, and the library's other defaults (a cutoff of 0.97, up to 1024
 * phases, a Kaiser window of beta 9), with floating-point planar samples in and out. As all three rates divide 48 kHz,
 * the filter has as many phases as the ratio (6, 4 or 3), and every output sample is exactly one phase after the last.
 *
 * <p>What FFmpeg does with it, which {@link OpusStream} already does around this class. The context is opened on the
 * first SILK or hybrid frame, with {@code silk_resample_delay[bandwidth]} samples of silence at the input rate fed in
 * first, which are 4, 8, 11, 11 and 11 samples for narrowband to fullband. Every SILK frame is then converted with room
 * for the Opus frame's samples at 48 kHz. Because the filter reaches ahead by 8 samples and the audio starts with the
 * mirror image of its first samples, libswresample holds samples back: the first frame comes out short by the number of
 * output samples the silence falls short of 8 input samples, which is 24 for narrowband and none for the others (for
 * wideband it holds 9 samples more than it gives). {@link OpusStream} counts the samples that are short as delayed,
 * and has them flushed out when the stream switches to CELT, changes SILK's rate, or ends: the flush reflects the
 * last samples to finish the filter ({@link #flush}). The context is closed after each flush, and opened again with
 * its silence by the next SILK frame.
 *
 * <p>State kept between calls: the input samples that no output has used up, for each output channel, and the filter's
 * position among them. {@link #close} forgets all of it.
 */
final class SilkResampler {

    private static final int OUTPUT_SAMPLE_RATE = 48000;
    // libswresample's default cutoff, which it uses when none is set, and its default number of phases, 1 << 10.
    private static final double CUTOFF = 0.97;
    private static final int PHASE_SHIFT = 10;
    // The samples of silence to feed in first, by bandwidth: FFmpeg's silk_resample_delay.
    private static final int[] SILENCE_SAMPLES = {4, 8, 11, 11, 11};
    // The most input samples held: a frame's, those the filter still reaches over, the silence and the mirror image.
    private static final int BUFFER_SIZE = 2 * SilkDecoder.MAX_FRAME_SAMPLES;

    private enum Start {
        // Until it has the first samples, the audio doesn't start: libswresample waits for enough to mirror.
        AWAITING_SAMPLES,
        STARTED
    }

    private final int outputChannels;
    private final float[][] samples;
    private final ResamplerFilter[] filters = new ResamplerFilter[OpusBandwidth.WIDEBAND.ordinal() + 1];
    private ResamplerState state = ResamplerState.CLOSED;
    private ResamplerFilter filter;
    private int inputSampleRate;
    private Start start = Start.AWAITING_SAMPLES;
    // How many samples of the buffer are in use, and where in them the filter for the next output sample begins.
    private int sampleCount;
    private ResamplerCursor cursor = new ResamplerCursor(0);

    /**
     * @param outputChannels how many channels are resampled, 1 or 2
     */
    SilkResampler(final int outputChannels) {
        this.outputChannels = outputChannels;
        this.samples = new float[outputChannels][BUFFER_SIZE];
        for (final var bandwidth : OpusBandwidth.values()) {
            if (bandwidth.ordinal() < filters.length) {
                filters[bandwidth.ordinal()] =
                        new ResamplerFilter(bandwidth.silkSampleRate(), OUTPUT_SAMPLE_RATE, CUTOFF, PHASE_SHIFT);
            }
        }
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
        filter = filters[bandwidth.silkBandwidth().ordinal()];
        start = Start.AWAITING_SAMPLES;
        cursor = new ResamplerCursor(0);
        // The first filter reaches over as many samples before the audio as the mirror image will fill in.
        sampleCount = centre();
        appendSilence(SILENCE_SAMPLES[bandwidth.ordinal()]);
    }

    /**
     * Converts a frame's samples, as {@code swr_convert} does: it takes all of them, and gives as many output samples
     * as the filter can finish with the samples so far, up to the capacity. The rest wait for the next call.
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
        for (var channel = 0; channel < outputChannels; channel++) {
            System.arraycopy(input[channel], 0, samples[channel], sampleCount, inputSamples);
        }
        sampleCount += inputSamples;
        mirrorStartWhenReady();
        final var written = produce(output, outputOffset, outputCapacity);
        discardConsumedSamples();
        return written;
    }

    /**
     * Writes the samples that the resampler held back, as {@code swr_convert} does when it is given no input and is
     * asked for as many as it owes: the last samples are reflected to give the filter what it reaches over past the
     * end. Samples it can't make are zero.
     *
     * @param output where to write the samples at 48 kHz, one array per output channel
     * @param outputOffset the index in each to start at
     * @param outputSamples how many samples to write to each, which is how many FFmpeg's decoder is short of the Opus
     *     frames' lengths so far
     */
    void flush(final float[][] output, final int outputOffset, final int outputSamples) {
        reflectEnd();
        final var written = produce(output, outputOffset, outputSamples);
        for (var channel = 0; channel < outputChannels; channel++) {
            Arrays.fill(output[channel], outputOffset + written, outputOffset + outputSamples, 0f);
        }
    }

    /**
     * Closes the resampler, forgetting its history, as {@code swr_close} does.
     */
    void close() {
        state = ResamplerState.CLOSED;
    }

    // How many samples the filter reaches back over from the sample it is centred on.
    private int centre() {
        return (filter.filterLength() - 1) / 2;
    }

    private void appendSilence(final int silenceSamples) {
        for (var channel = 0; channel < outputChannels; channel++) {
            Arrays.fill(samples[channel], sampleCount, sampleCount + silenceSamples, 0f);
        }
        sampleCount += silenceSamples;
    }

    // The audio before its first sample is the mirror image of what comes after it, which the filters of the first
    // output samples reach over. libswresample makes it once it has a filter's length and one more samples.
    private void mirrorStartWhenReady() {
        if (start == Start.STARTED || sampleCount - centre() < filter.filterLength() + 1) {
            return;
        }
        for (var channel = 0; channel < outputChannels; channel++) {
            for (var distance = 1; distance <= centre(); distance++) {
                samples[channel][centre() - distance] = samples[channel][centre() + distance];
            }
        }
        start = Start.STARTED;
    }

    // Makes the output samples whose filters lie within the input so far, each a phase on from the last.
    private int produce(final float[][] output, final int outputOffset, final int outputCapacity) {
        if (start == Start.AWAITING_SAMPLES) {
            return 0;
        }
        var written = 0;
        while (written < outputCapacity && cursor.position() + filter.filterLength() <= sampleCount) {
            for (var channel = 0; channel < outputChannels; channel++) {
                output[channel][outputOffset + written] =
                        filter.outputSample(samples[channel], cursor.position(), cursor.phase(), cursor.fraction());
            }
            cursor.advance(filter);
            written++;
        }
        return written;
    }

    // Keeps only the samples that a later output sample's filter reaches over.
    private void discardConsumedSamples() {
        if (start == Start.AWAITING_SAMPLES || cursor.position() == 0) {
            return;
        }
        final var consumed = cursor.position();
        for (var channel = 0; channel < outputChannels; channel++) {
            System.arraycopy(samples[channel], consumed, samples[channel], 0, sampleCount - consumed);
        }
        sampleCount -= consumed;
        cursor.dropInput(consumed);
    }

    // Appends the mirror image of the last of the samples that haven't been used up, as many as half a filter's length
    // at most.
    private void reflectEnd() {
        final var unused = sampleCount - cursor.position();
        final var reflection = (Math.min(unused, filter.filterLength()) + 1) / 2;
        for (var channel = 0; channel < outputChannels; channel++) {
            for (var distance = 0; distance < reflection; distance++) {
                samples[channel][sampleCount + distance] = samples[channel][sampleCount - distance - 1];
            }
        }
        sampleCount += reflection;
    }
}
