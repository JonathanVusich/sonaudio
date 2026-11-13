package dev.javax.sonaudio.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ResamplerCursorTest {

    // 8 kHz to 48 kHz has 6 phases, and each output sample is one phase on from the last.
    @Test
    void stepsOnePhaseForEachOutputSampleWhenUpsamplingBySix() {
        final var filter = new ResamplerFilter(8000, 48000, 0.97, 10);
        final var cursor = new ResamplerCursor(0);

        for (var output = 0; output < 6; output++) {
            assertThat(cursor.phase()).isEqualTo(output);
            assertThat(cursor.position()).isZero();
            cursor.advance(filter);
        }

        assertThat(cursor.phase()).isZero();
        assertThat(cursor.position()).isEqualTo(1);
    }

    // 11025 output samples at 11.025 kHz are exactly one second, which is 48000 input samples at 48 kHz.
    @Test
    void movesOnByTheRatioOfTheRatesWhenDownsampling() {
        final var filter = new ResamplerFilter(48000, 11025, 0.8, 8);
        final var cursor = new ResamplerCursor(-7);

        for (var output = 0; output < 11025; output++) {
            cursor.advance(filter);
        }

        assertThat(cursor.position()).isEqualTo(-7 + 48000);
        assertThat(cursor.phase()).isZero();
        assertThat(cursor.fraction()).isZero();
    }

    @Test
    void dropsInputFromThePosition() {
        final var cursor = new ResamplerCursor(10);

        cursor.dropInput(4);

        assertThat(cursor.position()).isEqualTo(6);
    }
}
