package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CeltNoiseTest {

    @Test
    void stepsTheLinearCongruentialGeneratorFromItsSeed() {
        final var noise = new CeltNoise();
        noise.seed(1);

        assertThat(noise.next()).isEqualTo(1664525 + 1013904223);
        assertThat(noise.next()).isEqualTo(1664525 * (1664525 + 1013904223) + 1013904223);
    }

    @Test
    void startsFromZeroAgainAfterAReset() {
        final var noise = new CeltNoise();
        noise.seed(12345);
        noise.next();

        noise.reset();

        assertThat(noise.next()).isEqualTo(1013904223);
    }
}
