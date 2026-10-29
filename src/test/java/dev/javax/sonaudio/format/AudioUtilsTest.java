package dev.javax.sonaudio.format;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AudioUtilsTest {

    @Test
    void durationOfWholeSeconds() {
        assertThat(AudioUtils.duration(88200, 44100)).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void durationKeepsFractionOfASecond() {
        assertThat(AudioUtils.duration(46080, 44100)).isEqualTo(Duration.ofSeconds(1, 44_897_959));
    }
}
