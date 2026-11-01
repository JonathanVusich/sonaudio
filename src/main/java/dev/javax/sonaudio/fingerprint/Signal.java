package dev.javax.sonaudio.fingerprint;

import java.io.IOException;
import java.util.Arrays;
import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.FloatPcmAudio;
import dev.javax.sonaudio.format.IntPcmAudio;
import dev.javax.sonaudio.format.IntPcmFrame;

/**
 * The start of a file's audio as {@code fpcalc} reads it: 16-bit mono samples at 11025 Hz, the first two minutes at
 * most. It brings the audio there with the filter FFmpeg's libswresample uses for {@code fpcalc} (see
 * {@link Resampler}), so the samples match {@code fpcalc}'s to within rounding.
 */
public final class Signal {

    public static final int SAMPLE_RATE = 11025;

    private static final int MAX_SAMPLES = 120 * SAMPLE_RATE;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int WIDE_BITS_PER_SAMPLE = 32;
    private static final int FIRST_CAPACITY = 1 << 16;
    private static final float HALF = 0.5f;
    private static final int RESAMPLE_FIRST_WEIGHT = 1;

    private final int[] samples;

    /**
     * Decodes only as much of the audio as it needs.
     *
     * @throws IOException when the audio can't be read, or ends before a frame does
     * @throws InvalidFormatException when the audio is corrupt
     */
    public Signal(final IntPcmAudio audio) throws IOException, InvalidFormatException {
        this.samples = readSamples(audio);
    }

    /**
     * Decodes only as much of the audio as it needs, and stops where its decoder dropped a frame it couldn't decode.
     *
     * @throws IOException when the audio can't be read, or ends before a frame does
     * @throws InvalidFormatException when the audio is corrupt
     */
    public Signal(final FloatPcmAudio audio) throws IOException, InvalidFormatException {
        this.samples = new FloatSignalReader(MAX_SAMPLES).read(audio);
    }

    public int sampleCount() {
        return samples.length;
    }

    public int sample(final int index) {
        return samples[index];
    }

    private static int[] readSamples(final IntPcmAudio audio) throws IOException, InvalidFormatException {
        final var inputRate = audio.sampleRate();
        if (inputRate == SAMPLE_RATE) {
            final var channels = readChannels(audio, MAX_SAMPLES, Mixing.AFTER);
            return truncated(unresampledSamples(channels, audio.bitsPerSample()));
        }
        final var resampler = new Resampler(inputRate, SAMPLE_RATE);
        final var channels = readChannels(audio, resampler.inputNeeded(MAX_SAMPLES), Mixing.BEFORE_RESAMPLING);
        return truncated(resampledSamples(channels, resampler));
    }

    private static int[] truncated(final int[] samples) {
        return Arrays.copyOf(samples, Math.min(samples.length, MAX_SAMPLES));
    }

    // Whether the channels are mixed down as they're read, which libswresample does when it resamples after mixing.
    private enum Mixing {
        BEFORE_RESAMPLING,
        AFTER
    }

    /**
     * The audio as floats between -1 and 1, one array per channel, stopping once there are samples enough. With
     * {@code BEFORE_RESAMPLING}, a layout that libswresample mixes down before it resamples is read as one mixed
     * channel, to hold two minutes of it in memory rather than of every channel.
     */
    private static Channels readChannels(final IntPcmAudio audio, final int capacity, final Mixing mixing)
            throws IOException, InvalidFormatException {
        final var bitsPerSample = audio.bitsPerSample();
        var samples = new float[0][];
        var layout = ChannelLayout.SEPARATE;
        var length = 0;
        var ending = Ending.ENDED;
        while (audio.hasNextFrame()) {
            final var frame = audio.nextFrame();
            layout = channelLayout(frame, audio, mixing);
            final var copied = Math.min(frame.sampleCount(), capacity - length);
            samples = grown(samples, readCount(frame, layout), length + copied, capacity);
            copyFrame(frame, bitsPerSample, layout, samples, length, copied);
            length += copied;
            if (length == capacity) {
                ending = Ending.FILLED;
                break;
            }
        }
        return new Channels(samples, length, ending, layout);
    }

    private static ChannelLayout channelLayout(final IntPcmFrame frame, final IntPcmAudio audio, final Mixing mixing) {
        if (mixing == Mixing.BEFORE_RESAMPLING && !resamplesFirst(frame.channelCount(), audio.sampleRate())) {
            return ChannelLayout.MIXED;
        }
        return ChannelLayout.SEPARATE;
    }

    private static int readCount(final IntPcmFrame frame, final ChannelLayout layout) {
        if (layout == ChannelLayout.MIXED) {
            return 1;
        }
        return frame.channelCount();
    }

    private static void copyFrame(
            final IntPcmFrame frame,
            final int bitsPerSample,
            final ChannelLayout layout,
            final float[][] samples,
            final int offset,
            final int count) {
        for (var i = 0; i < count; i++) {
            if (layout == ChannelLayout.MIXED) {
                samples[0][offset + i] = mix(frame, i, bitsPerSample);
                continue;
            }
            for (var channel = 0; channel < samples.length; channel++) {
                samples[channel][offset + i] = toFloat(frame.channelSample(channel, i), bitsPerSample);
            }
        }
    }

    private static float[][] grown(final float[][] channels, final int count, final int needed, final int capacity) {
        if (channels.length == 0) {
            return new float[count][Math.min(Math.max(needed, FIRST_CAPACITY), capacity)];
        }
        if (channels[0].length >= needed) {
            return channels;
        }
        final var size = Math.min(Math.max(needed, 2 * channels[0].length), capacity);
        final var larger = new float[channels.length][];
        for (var channel = 0; channel < larger.length; channel++) {
            larger[channel] = Arrays.copyOf(channels[channel], size);
        }
        return larger;
    }

    // Audio of 16 bits or less is 16-bit samples to libswresample, and any more is 32-bit ones.
    private static float toFloat(final int sample, final int bitsPerSample) {
        if (bitsPerSample <= BITS_PER_SAMPLE) {
            return (sample << (BITS_PER_SAMPLE - bitsPerSample)) * (1.0f / (1 << (BITS_PER_SAMPLE - 1)));
        }
        return (float) (sample << (WIDE_BITS_PER_SAMPLE - bitsPerSample)) * (1.0f / (1L << (WIDE_BITS_PER_SAMPLE - 1)));
    }

    // Stereo is libswresample's two halves. Other layouts aren't ported, so they're averaged.
    // TODO: mix more than two channels as libswresample does for their layout.
    private static float mix(final IntPcmFrame frame, final int index, final int bitsPerSample) {
        if (frame.channelCount() == 1) {
            return toFloat(frame.channelSample(0, index), bitsPerSample);
        }
        if (frame.channelCount() == 2) {
            return HALF * toFloat(frame.channelSample(0, index), bitsPerSample)
                    + HALF * toFloat(frame.channelSample(1, index), bitsPerSample);
        }
        var sum = 0f;
        for (var channel = 0; channel < frame.channelCount(); channel++) {
            sum += toFloat(frame.channelSample(channel, index), bitsPerSample);
        }
        return sum / frame.channelCount();
    }

    // At the target rate libswresample only converts: 16-bit audio is mixed down in integers, and wider audio in
    // floats.
    private static int[] unresampledSamples(final Channels channels, final int bitsPerSample) {
        if (bitsPerSample > BITS_PER_SAMPLE || channels.count() > 2) {
            return toSixteenBits(mixedDown(channels.samples(), channels.length()), channels.length());
        }
        final var mono = new int[channels.length()];
        for (var i = 0; i < mono.length; i++) {
            mono[i] = integerMono(channels.samples(), i);
        }
        return mono;
    }

    private static int integerMono(final float[][] channels, final int index) {
        final var first = (int) (channels[0][index] * (1 << (BITS_PER_SAMPLE - 1)));
        if (channels.length == 1) {
            return first;
        }
        final var second = (int) (channels[1][index] * (1 << (BITS_PER_SAMPLE - 1)));
        return (first * 16384 + second * 16384 + 16384) >> 15;
    }

    private static int[] resampledSamples(final Channels channels, final Resampler resampler) {
        if (channels.count() == 0) {
            return new int[0];
        }
        if (channels.layout() == ChannelLayout.MIXED) {
            final var resampled = resample(channels, channels.samples()[0], resampler);
            return toSixteenBits(resampled, resampled.length);
        }
        final var resampled = new float[channels.count()][];
        for (var channel = 0; channel < resampled.length; channel++) {
            resampled[channel] = resample(channels, channels.samples()[channel], resampler);
        }
        return toSixteenBits(mixedDown(resampled, resampled[0].length), resampled[0].length);
    }

    private static float[] resample(final Channels channels, final float[] samples, final Resampler resampler) {
        if (channels.ending() == Ending.ENDED) {
            return resampler.resampleEnded(samples, channels.length());
        }
        return resampler.resampleOpen(samples, channels.length());
    }

    // libswresample decides whether to resample before it mixes the channels down by comparing the channel counts'
    // ratio
    // to the rates' in a mix of integer and floating-point arithmetic, so from two channels or more to one it's always
    // before.
    private static boolean resamplesFirst(final int channelCount, final int inputRate) {
        final var channelRatio = RESAMPLE_FIRST_WEIGHT * 1 / channelCount - RESAMPLE_FIRST_WEIGHT;
        return channelRatio < SAMPLE_RATE / (float) inputRate - 1.0;
    }

    private static float[] mixedDown(final float[][] channels, final int length) {
        if (channels.length == 1) {
            return channels[0];
        }
        final var mono = new float[length];
        for (var i = 0; i < length; i++) {
            mono[i] = mixChannels(channels, i);
        }
        return mono;
    }

    private static float mixChannels(final float[][] channels, final int index) {
        if (channels.length == 2) {
            return HALF * channels[0][index] + HALF * channels[1][index];
        }
        var sum = 0f;
        for (final var channel : channels) {
            sum += channel[index];
        }
        return sum / channels.length;
    }

    private static int[] toSixteenBits(final float[] mono, final int length) {
        final var samples = new int[length];
        for (var i = 0; i < length; i++) {
            final var rounded = (int) Math.rint(mono[i] * (1 << (BITS_PER_SAMPLE - 1)));
            samples[i] = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, rounded));
        }
        return samples;
    }

    // Whether the channels were mixed down into one as they were read.
    private enum ChannelLayout {
        MIXED,
        SEPARATE
    }

    // Whether the audio ended before there were samples enough.
    private enum Ending {
        ENDED,
        FILLED
    }

    private record Channels(float[][] samples, int length, Ending ending, ChannelLayout layout) {

        int count() {
            return samples.length;
        }
    }
}
