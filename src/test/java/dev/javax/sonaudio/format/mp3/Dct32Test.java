package dev.javax.sonaudio.format.mp3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Random;
import org.junit.jupiter.api.Test;

class Dct32Test {

    private static final int SIZE = 32;

    // The DCT-II without the 1/sqrt(2) scaling of its first value: out[k] = sum of in[n] * cos((2n + 1) * k * pi / 64).
    @Test
    void transformsAsTheDirectSum() {
        final var random = new Random(3);
        final var dct = new Dct32();
        final var input = new float[SIZE];
        final var output = new float[SIZE];
        for (var round = 0; round < 20; round++) {
            for (var index = 0; index < SIZE; index++) {
                input[index] = (float) random.nextGaussian();
            }

            dct.transform(output, 0, input, 0);

            for (var frequency = 0; frequency < SIZE; frequency++) {
                var expected = 0.0;
                for (var index = 0; index < SIZE; index++) {
                    expected += input[index] * Math.cos((2 * index + 1) * frequency * Math.PI / 64);
                }
                assertThat((double) output[frequency])
                        .as("round %d frequency %d", round, frequency)
                        .isCloseTo(expected, within(1e-4));
            }
        }
    }
}
