package dev.javax.sonaudio.fingerprint;

import static dev.javax.sonaudio.fingerprint.SignalUtils.integerMono;
import static dev.javax.sonaudio.fingerprint.SignalUtils.mix;
import static dev.javax.sonaudio.fingerprint.SignalUtils.mixedDown;
import static dev.javax.sonaudio.fingerprint.SignalUtils.resamplesFirst;
import static dev.javax.sonaudio.fingerprint.SignalUtils.toFloat;
import static dev.javax.sonaudio.fingerprint.SignalUtils.toSixteenBits;

import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.IntPcmAudio;
import dev.javax.sonaudio.format.IntPcmFrame;
import java.io.IOException;
import java.util.Arrays;

/**
 * Brings audio a decoder made in integers to Chromaprint's 16-bit mono samples at 11025 Hz, as {@code fpcalc} does
 * with libswresample: converted to floats, then mixed down and resampled in the order libswresample picks for the
 * channel layout and rates, or, at 11025 Hz, only converted.
 */
final class IntSignalReader {

    private static final int SAMPLE_RATE = Signal.SAMPLE_RATE;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int FIRST_CAPACITY = 1 << 16;

    private final int maxSamples;

    IntSignalReader(final int maxSamples) {
        this.maxSamples = maxSamples;
    }

    /**
     * @return at most {@code maxSamples} samples
     */
    int[] readSignal(final IntPcmAudio audio) throws IOException, InvalidFormatException {
        final var inputRate = audio.sampleRate();
        if (inputRate == SAMPLE_RATE) {
            final var channels = readChannels(audio, maxSamples, Mixing.AFTER);
            return truncated(unresampledSamples(channels, audio.bitsPerSample()));
        }
        final var resampler = new Resampler(inputRate, SAMPLE_RATE);
        final var channels = readChannels(audio, resampler.inputNeeded(maxSamples), Mixing.BEFORE_RESAMPLING);
        return truncated(resampledSamples(channels, resampler));
    }

    private int[] truncated(final int[] samples) {
        return Arrays.copyOf(samples, Math.min(samples.length, maxSamples));
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
    private Channels readChannels(final IntPcmAudio audio, final int capacity, final Mixing mixing)
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

    private ChannelLayout channelLayout(final IntPcmFrame frame, final IntPcmAudio audio, final Mixing mixing) {
        if (mixing == Mixing.BEFORE_RESAMPLING && !resamplesFirst(frame.channelCount(), audio.sampleRate())) {
            return ChannelLayout.MIXED;
        }
        return ChannelLayout.SEPARATE;
    }

    private int readCount(final IntPcmFrame frame, final ChannelLayout layout) {
        if (layout == ChannelLayout.MIXED) {
            return 1;
        }
        return frame.channelCount();
    }

    private void copyFrame(
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

    private float[][] grown(final float[][] channels, final int count, final int needed, final int capacity) {
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

    // At the target rate libswresample only converts: 16-bit audio is mixed down in integers, and wider audio in
    // floats.
    private int[] unresampledSamples(final Channels channels, final int bitsPerSample) {
        if (bitsPerSample > BITS_PER_SAMPLE || channels.count() > 2) {
            return toSixteenBits(mixedDown(channels.samples(), channels.length()), channels.length());
        }
        final var mono = new int[channels.length()];
        for (var i = 0; i < mono.length; i++) {
            mono[i] = integerMono(channels.samples(), i);
        }
        return mono;
    }

    private int[] resampledSamples(final Channels channels, final Resampler resampler) {
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

    private float[] resample(final Channels channels, final float[] samples, final Resampler resampler) {
        if (channels.ending() == Ending.ENDED) {
            return resampler.resampleEnded(samples, channels.length());
        }
        return resampler.resampleOpen(samples, channels.length());
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
