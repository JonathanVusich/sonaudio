package dev.javax.sonaudio.fingerprint;

import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.FloatPcmAudio;
import dev.javax.sonaudio.format.FloatPcmFrame;
import dev.javax.sonaudio.format.FrameContinuity;
import java.io.IOException;
import java.util.Arrays;

/**
 * Brings audio a decoder made in floating point to Chromaprint's 16-bit mono samples at 11025 Hz, as {@code fpcalc}
 * does with libswresample: each stretch of audio with one sample rate and
 * channel count has its channels resampled on their own ({@link Resampler}) and mixed in floating point, and only
 * then are the samples rounded to 16 bits.
 */
final class FloatSignalReader {

    private static final int SAMPLE_RATE = Signal.SAMPLE_RATE;
    // Enough samples past the last that a stretch's end, which reflects the audio, doesn't change any that are kept.
    private static final int MARGIN = 1000;
    private static final float FULL_SCALE = 32768;

    private final int maxSamples;

    FloatSignalReader(final int maxSamples) {
        this.maxSamples = maxSamples;
    }

    /**
     * @return at most {@code maxSamples} samples
     */
    int[] readSignal(final FloatPcmAudio audio) throws IOException, InvalidFormatException {
        if (!audio.hasNextFrame()) {
            return new int[0];
        }
        final var first = audio.nextFrame();
        if (first.continuity() == FrameContinuity.AFTER_DECODING_ERROR) {
            return new int[0];
        }
        final var stretches = readStretches(audio, first);
        return toSixteenBits(stretches);
    }

    /**
     * @return each stretch of the audio resampled to mono, as far as the signal needs, and stopping at a frame after
     *     one the decoder dropped
     */
    private float[][] readStretches(final FloatPcmAudio audio, final FloatPcmFrame first)
            throws IOException, InvalidFormatException {
        var stretch = new Stretch(first.sampleRate(), first.channelCount());
        var finished = new float[0][];
        var finishedCount = 0;
        var frame = first;
        while (true) {
            if (frame.sampleRate() != stretch.sampleRate() || frame.channelCount() != stretch.channelCount()) {
                final var resampled = stretch.resampledMono();
                finished = appended(finished, resampled);
                finishedCount += resampled.length;
                stretch = new Stretch(frame.sampleRate(), frame.channelCount());
            }
            stretch.addFrame(frame);
            if (finishedCount + stretch.outputEstimate() >= maxSamples + MARGIN || !audio.hasNextFrame()) {
                return appended(finished, stretch.resampledMono());
            }
            frame = audio.nextFrame();
            if (frame.continuity() == FrameContinuity.AFTER_DECODING_ERROR) {
                return appended(finished, stretch.resampledMono());
            }
        }
    }

    private float[][] appended(final float[][] stretches, final float[] stretch) {
        final var longer = Arrays.copyOf(stretches, stretches.length + 1);
        longer[stretches.length] = stretch;
        return longer;
    }

    private int[] toSixteenBits(final float[][] stretches) {
        final var samples = new int[maxSamples];
        var length = 0;
        for (final var stretch : stretches) {
            for (var index = 0; index < stretch.length && length < maxSamples; index++) {
                final var rounded = Math.rint(stretch[index] * FULL_SCALE);
                samples[length++] = (int) Math.clamp(rounded, Short.MIN_VALUE, Short.MAX_VALUE);
            }
        }
        return Arrays.copyOf(samples, length);
    }

    /**
     * Audio with one sample rate and channel count. As libswresample does when it takes channels away, each channel
     * is resampled on its own, and the resampled channels are mixed to mono.
     */
    private static final class Stretch {

        private final int sampleRate;
        private final int channelCount;
        private float[][] channels;
        private int length;

        Stretch(final int sampleRate, final int channelCount) {
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
            this.channels = new float[channelCount][1 << 16];
        }

        int sampleRate() {
            return sampleRate;
        }

        int channelCount() {
            return channelCount;
        }

        /**
         * @return how many samples at 11025 Hz the audio so far will make
         */
        long outputEstimate() {
            return (long) length * SAMPLE_RATE / sampleRate;
        }

        void addFrame(final FloatPcmFrame frame) {
            if (length + frame.sampleCount() > channels[0].length) {
                final var size = Math.max(channels[0].length * 2, length + frame.sampleCount());
                for (var channel = 0; channel < channelCount; channel++) {
                    channels[channel] = Arrays.copyOf(channels[channel], size);
                }
            }
            for (var channel = 0; channel < channelCount; channel++) {
                for (var index = 0; index < frame.sampleCount(); index++) {
                    channels[channel][length + index] = frame.channelSample(channel, index);
                }
            }
            length += frame.sampleCount();
        }

        float[] resampledMono() {
            final var resampled = new float[channelCount][];
            for (var channel = 0; channel < channelCount; channel++) {
                resampled[channel] = resampledChannel(channels[channel]);
            }
            return mixedMono(resampled);
        }

        private float[] resampledChannel(final float[] samples) {
            if (sampleRate == SAMPLE_RATE) {
                return Arrays.copyOf(samples, length);
            }
            return new Resampler(sampleRate, SAMPLE_RATE).resampleEnded(samples, length);
        }

        // swresample's mix to mono: each channel of stereo counts half, and with more channels, an equal share.
        private float[] mixedMono(final float[][] resampled) {
            if (channelCount == 1) {
                return resampled[0];
            }
            final var mono = new float[resampled[0].length];
            for (var index = 0; index < mono.length; index++) {
                mono[index] = mixedSample(resampled, index);
            }
            return mono;
        }

        private float mixedSample(final float[][] resampled, final int index) {
            if (channelCount == 2) {
                return 0.5f * resampled[0][index] + 0.5f * resampled[1][index];
            }
            var sum = 0f;
            for (var channel = 0; channel < channelCount; channel++) {
                sum += resampled[channel][index];
            }
            return sum / channelCount;
        }
    }
}
