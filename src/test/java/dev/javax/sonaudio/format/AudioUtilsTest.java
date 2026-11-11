package dev.javax.sonaudio.format;

import static org.assertj.core.api.Assertions.assertThat;

import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.flac.FlacParser;
import dev.javax.sonaudio.format.flac.VerbatimFlac;
import dev.javax.sonaudio.format.mp3.Mp3Parser;
import dev.javax.sonaudio.samples.FlacAudioSample;
import dev.javax.sonaudio.samples.Mp3Sample;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AudioUtilsTest {

    @Test
    void durationOfWholeSeconds() {
        assertThat(AudioUtils.duration(88200, 44100)).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void durationKeepsFractionOfASecond() {
        assertThat(AudioUtils.duration(46080, 44100)).isEqualTo(Duration.ofSeconds(1, 44_897_959));
    }

    @ParameterizedTest
    @EnumSource(FlacAudioSample.class)
    void measureTheDurationOfAllTheIntegerAudio(final FlacAudioSample sample)
            throws IOException, InvalidFormatException {
        try (final var channel = sample.channel()) {
            final var audio = new FlacParser().parse(channel).audio();

            assertThat(AudioUtils.measureDuration(audio))
                    .isEqualTo(audio.streamInfo().duration());
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = Mp3Sample.class,
            names = {"STEREO_CBR_ID3V24", "HE_32KHZ"})
    void measureTheDurationOfAllTheFloatAudio(final Mp3Sample sample) throws IOException, InvalidFormatException {
        try (final var channel = sample.channel()) {
            final var audio = new Mp3Parser().parse(channel).audio();

            assertThat(AudioUtils.measureDuration(audio))
                    .isEqualTo(audio.streamInfo().duration());
        }
    }

    @Test
    void audioWithNoFramesLastsNoTime() throws IOException, InvalidFormatException {
        final var audio = new VerbatimFlac(44100, 16, new int[2][0]).audio();

        assertThat(AudioUtils.measureDuration(audio)).isZero();
    }
}
