package dev.javax.sonaudio.format;

import java.time.Duration;
import lombok.experimental.UtilityClass;

@UtilityClass
public final class AudioUtils {

    /**
     * @param samples per channel
     * @return how long that many samples play at the sample rate
     */
    public static Duration duration(final long samples, final int sampleRate) {
        final var nanos = (samples % sampleRate) * 1_000_000_000L / sampleRate;
        return Duration.ofSeconds(samples / sampleRate, nanos);
    }
}
