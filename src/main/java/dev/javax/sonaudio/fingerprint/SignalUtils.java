package dev.javax.sonaudio.fingerprint;

import dev.javax.sonaudio.format.IntPcmFrame;
import lombok.experimental.UtilityClass;

/**
 * The conversions libswresample makes between sample formats and channel layouts for {@code fpcalc}, which the signal
 * readers bring audio to 16-bit mono with.
 */
@UtilityClass
class SignalUtils {

    private static final int BITS_PER_SAMPLE = 16;
    private static final int WIDE_BITS_PER_SAMPLE = 32;
    private static final float HALF = 0.5f;
    private static final int RESAMPLE_FIRST_WEIGHT = 1;

    /**
     * Audio of 16 bits or less is 16-bit samples to libswresample, and any more is 32-bit ones.
     *
     * @return the sample between -1 and 1
     */
    static float toFloat(final int sample, final int bitsPerSample) {
        if (bitsPerSample <= BITS_PER_SAMPLE) {
            return (sample << (BITS_PER_SAMPLE - bitsPerSample)) * (1.0f / (1 << (BITS_PER_SAMPLE - 1)));
        }
        return (float) (sample << (WIDE_BITS_PER_SAMPLE - bitsPerSample)) * (1.0f / (1L << (WIDE_BITS_PER_SAMPLE - 1)));
    }

    /**
     * Stereo is libswresample's two halves. Other layouts aren't ported, so they're averaged.
     *
     * @return the frame's channels at the index mixed to one sample between -1 and 1
     */
    // TODO: mix more than two channels as libswresample does for their layout.
    static float mix(final IntPcmFrame frame, final int index, final int bitsPerSample) {
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

    /**
     * libswresample mixes 16-bit stereo down in integers at the rate it doesn't change.
     *
     * @return the one or two channels at the index as one 16-bit sample
     */
    static int integerMono(final float[][] channels, final int index) {
        final var first = (int) (channels[0][index] * (1 << (BITS_PER_SAMPLE - 1)));
        if (channels.length == 1) {
            return first;
        }
        final var second = (int) (channels[1][index] * (1 << (BITS_PER_SAMPLE - 1)));
        return (first * 16384 + second * 16384 + 16384) >> 15;
    }

    /**
     * @return the first {@code length} samples of the channels mixed to one, which is the one channel itself when
     *     there's only one
     */
    static float[] mixedDown(final float[][] channels, final int length) {
        if (channels.length == 1) {
            return channels[0];
        }
        final var mono = new float[length];
        for (var i = 0; i < length; i++) {
            mono[i] = mixChannels(channels, i);
        }
        return mono;
    }

    // Stereo is libswresample's two halves, and more channels an equal share each.
    static float mixChannels(final float[][] channels, final int index) {
        if (channels.length == 2) {
            return HALF * channels[0][index] + HALF * channels[1][index];
        }
        var sum = 0f;
        for (final var channel : channels) {
            sum += channel[index];
        }
        return sum / channels.length;
    }

    /**
     * @return the first {@code length} samples rounded to 16 bits, clipped to their range
     */
    static int[] toSixteenBits(final float[] mono, final int length) {
        final var samples = new int[length];
        for (var i = 0; i < length; i++) {
            final var rounded = (int) Math.rint(mono[i] * (1 << (BITS_PER_SAMPLE - 1)));
            samples[i] = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, rounded));
        }
        return samples;
    }

    /**
     * libswresample decides whether to resample before it mixes the channels down by comparing the channel counts'
     * ratio to the rates' in a mix of integer and floating-point arithmetic, so from two channels or more to one it's
     * always before.
     *
     * @return whether audio of this many channels at the rate is resampled before it's mixed down to 11025 Hz mono
     */
    static boolean resamplesFirst(final int channelCount, final int inputRate) {
        final var channelRatio = RESAMPLE_FIRST_WEIGHT * 1 / channelCount - RESAMPLE_FIRST_WEIGHT;
        return channelRatio < Signal.SAMPLE_RATE / (float) inputRate - 1.0;
    }
}
