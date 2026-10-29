package dev.javax.sonaudio.format.mp3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class SynthesisWindowTest {

    private static final double PRECISION = 1e-9;

    // Values from ISO/IEC 11172-3 Annex B, Table B.3.
    @Test
    void matchesSpec() {
        assertThat(SynthesisWindow.coefficient(0)).isZero();
        assertThat(SynthesisWindow.coefficient(1)).isCloseTo(-0.000015259, within(PRECISION));
        assertThat(SynthesisWindow.coefficient(255)).isCloseTo(-1.144287109, within(PRECISION));
        assertThat(SynthesisWindow.coefficient(256)).isCloseTo(1.144989014, within(PRECISION));
        assertThat(SynthesisWindow.coefficient(257)).isCloseTo(1.144287109, within(PRECISION));
        assertThat(SynthesisWindow.coefficient(511)).isCloseTo(0.000015259, within(PRECISION));
    }

    // The window's second half mirrors its first, negated but at multiples of 64.
    @Test
    void mirrorsAboutItsMiddle() {
        for (var index = 1; index < SynthesisWindow.SIZE / 2; index++) {
            final var mirrored = SynthesisWindow.coefficient(SynthesisWindow.SIZE - index);
            final var coefficient = SynthesisWindow.coefficient(index);

            if (index % 64 == 0) {
                assertThat(mirrored).isEqualTo(coefficient);
            } else {
                assertThat(mirrored).isEqualTo(-coefficient);
            }
        }
    }
}
