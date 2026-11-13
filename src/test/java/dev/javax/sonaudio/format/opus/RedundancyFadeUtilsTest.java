package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class RedundancyFadeUtilsTest {

    private static final int FADE = RedundancyFadeUtils.FADE_SAMPLES;

    @Test
    void fadesInByTheSquaredWindowOfCelt() {
        final var silence = new float[FADE];
        final var ones = new float[FADE];
        Arrays.fill(ones, 1f);
        final var target = new float[FADE];

        RedundancyFadeUtils.crossFade(target, 0, silence, 0, ones, 0, 0, FADE);

        // The values of FFmpeg's ff_celt_window2.
        assertThat(target[0]).isCloseTo(4.5275357e-09f, within(1e-12f));
        assertThat(target[1]).isCloseTo(3.66647e-07f, within(1e-12f));
        assertThat(target[60]).isCloseTo(0.51028f, within(1e-5f));
        assertThat(target[FADE - 1]).isCloseTo(1f, within(1e-6f));
    }

    @Test
    void fadesOutByWhatTheFadeInLeaves() {
        final var ones = new float[FADE];
        Arrays.fill(ones, 1f);
        final var silence = new float[FADE];
        final var faded = new float[FADE];

        RedundancyFadeUtils.crossFade(faded, 0, ones, 0, silence, 0, 0, FADE);

        assertThat(faded[0]).isCloseTo(1f, within(1e-6f));
        assertThat(faded[FADE - 1]).isCloseTo(0f, within(1e-6f));
    }

    @Test
    void addsUpToTheSameSignalWhenBothAreTheSame() {
        final var signal = new float[FADE];
        for (var index = 0; index < FADE; index++) {
            signal[index] = (float) Math.sin(index / 7.0);
        }
        final var target = new float[FADE];

        RedundancyFadeUtils.crossFade(target, 0, signal, 0, signal, 0, 0, FADE);

        for (var index = 0; index < FADE; index++) {
            assertThat(target[index]).isCloseTo(signal[index], within(1e-6f));
        }
    }

    @Test
    void mayWriteOverOneOfItsSourcesAndStartPartWayThroughTheWindow() {
        final var fadingOut = new float[FADE + 10];
        Arrays.fill(fadingOut, 2f);
        final var fadingIn = new float[FADE + 10];
        Arrays.fill(fadingIn, 4f);

        RedundancyFadeUtils.crossFade(fadingOut, 5, fadingOut, 5, fadingIn, 10, 100, 20);

        assertThat(fadingOut[4]).isEqualTo(2f);
        assertThat(fadingOut[5]).isGreaterThan(2f);
        assertThat(fadingOut[24]).isLessThanOrEqualTo(4f);
        assertThat(fadingOut[25]).isEqualTo(2f);
    }
}
