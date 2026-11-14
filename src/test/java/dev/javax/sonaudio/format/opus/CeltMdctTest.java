package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CeltMdctTest {

    private static final float SCALE = -1.0f / 32768;

    // The inverse transform written as the sums that define it: the first half of the output is the cosines of the
    // top of the window, the second half those of the bottom, negated.
    private static double[] directInverse(final float[] coefficients, final int size) {
        final var half = size / 2;
        final var samples = new double[size];
        for (var index = 0; index < half; index++) {
            var upper = 0.0;
            var lower = 0.0;
            for (var bin = 0; bin < size; bin++) {
                final var odd = 2 * bin + 1;
                upper += coefficients[bin] * Math.cos(odd * (2 * size - 2 * index - 1) * Math.PI / (4.0 * size));
                lower += coefficients[bin] * Math.cos(odd * (3 * size + 2 * index + 1) * Math.PI / (4.0 * size));
            }
            samples[index] = upper * SCALE;
            samples[half + index] = -lower * SCALE;
        }
        return samples;
    }

    @ParameterizedTest
    @ValueSource(ints = {120, 240, 480, 960})
    void matchesTheDefinitionOfTheInverseTransform(final int size) {
        final var random = new Random(size);
        final var coefficients = new float[size];
        for (var index = 0; index < size; index++) {
            coefficients[index] = (float) (random.nextGaussian() * 1000);
        }
        final var output = new float[size];

        new CeltMdct(size).inverse(coefficients, 0, 1, output, 0);

        final var expected = directInverse(coefficients, size);
        for (var index = 0; index < size; index++) {
            assertThat((double) output[index]).isCloseTo(expected[index], within(1e-3));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {120, 240})
    void readsInterleavedBlocksThroughTheStride(final int size) {
        final var blocks = 4;
        final var random = new Random(size);
        final var interleaved = new float[size * blocks];
        final var separate = new float[size];
        for (var index = 0; index < size; index++) {
            separate[index] = (float) random.nextGaussian();
            interleaved[index * blocks + 2] = separate[index];
        }
        final var strided = new float[size];
        final var direct = new float[size];
        final var mdct = new CeltMdct(size);

        mdct.inverse(interleaved, 2, blocks, strided, 0);
        mdct.inverse(separate, 0, 1, direct, 0);

        assertThat(strided).isEqualTo(direct);
    }
}
