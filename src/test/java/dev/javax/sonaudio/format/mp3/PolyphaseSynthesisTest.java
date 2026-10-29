package dev.javax.sonaudio.format.mp3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Random;
import org.junit.jupiter.api.Test;

class PolyphaseSynthesisTest {

    private static final double FULL_SCALE = 1 << 23;
    private static final int HISTORY = 1024;
    private static final int VALUES = 64;
    private static final int SUBBANDS = 32;

    // The textbook filterbank of ISO/IEC 11172-3 Figure A.2, in doubles: the matrixed values are pushed onto a history,
    // and the window's coefficients weight those 512 of them that its figure picks, in 16s.
    private static double[] textbook(final double[] history, final double[] subbands) {
        System.arraycopy(history, 0, history, VALUES, HISTORY - VALUES);
        for (var value = 0; value < VALUES; value++) {
            var sum = 0.0;
            for (var subband = 0; subband < SUBBANDS; subband++) {
                sum += Math.cos((16 + value) * (2 * subband + 1) * Math.PI / 64) * subbands[subband];
            }
            history[value] = sum;
        }
        final var samples = new double[SUBBANDS];
        for (var sample = 0; sample < SUBBANDS; sample++) {
            for (var step = 0; step < 16; step++) {
                final var start = (step / 2) * 128 + (step % 2) * 96;
                samples[sample] += history[start + sample] * SynthesisWindow.coefficient(sample + SUBBANDS * step);
            }
        }
        return samples;
    }

    @Test
    void matchesTextbookFilterbank() {
        final var random = new Random(1);
        final var synthesis = new PolyphaseSynthesis();
        final var history = new double[HISTORY];
        for (var instant = 0; instant < 40; instant++) {
            final var subbands = new double[SUBBANDS];
            for (var subband = 0; subband < SUBBANDS; subband++) {
                subbands[subband] = random.nextGaussian() * 0.1;
            }
            final SubbandSamples samples = (_, subband) -> (float) (subbands[subband] * FULL_SCALE);

            synthesis.synthesize(samples, 1);

            final var expected = textbook(history, subbands);
            for (var sample = 0; sample < SUBBANDS; sample++) {
                assertThat((double) synthesis.sample(sample))
                        .as("instant %d sample %d", instant, sample)
                        .isCloseTo(expected[sample], within(1e-5));
            }
        }
    }
}
