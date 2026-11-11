package dev.javax.sonaudio.format;

import dev.javax.sonaudio.exception.InvalidFormatException;
import java.io.IOException;
import java.time.Duration;

/**
 * Works out how long audio plays.
 */
public final class AudioUtils {

    private AudioUtils() {}

    /**
     * @param samples per channel
     * @param sampleRate samples per second, per channel
     * @return how long that many samples play at the sample rate
     */
    public static Duration duration(final long samples, final int sampleRate) {
        final var nanos = (samples % sampleRate) * 1_000_000_000L / sampleRate;
        return Duration.ofSeconds(samples / sampleRate, nanos);
    }

    /**
     * Decodes all of the audio, so that how long it plays comes from its frames and not from a header, which a bad
     * encoder or an edit can leave wrong. Frames are counted in runs at one sample rate, which a stream rarely changes,
     * so that a run's duration is worked out from its whole sample count, not rounded for each frame.
     *
     * @param audio the audio from its first frame, which this reads to its end
     * @return how long the audio plays, having read every frame of it
     * @throws IOException when the audio can't be read, or ends before a frame does
     * @throws InvalidFormatException when the audio is corrupt
     */
    public static Duration measureDuration(final PcmAudio audio) throws IOException, InvalidFormatException {
        var finishedRuns = Duration.ZERO;
        var runSampleRate = 0;
        var runSamples = 0L;
        while (audio.hasNextFrame()) {
            final var frame = audio.nextFrame();
            if (frame.sampleRate() != runSampleRate) {
                if (runSamples > 0) {
                    finishedRuns = finishedRuns.plus(duration(runSamples, runSampleRate));
                }
                runSampleRate = frame.sampleRate();
                runSamples = 0;
            }
            runSamples += frame.sampleCount();
        }
        return runSamples == 0 ? finishedRuns : finishedRuns.plus(duration(runSamples, runSampleRate));
    }
}
