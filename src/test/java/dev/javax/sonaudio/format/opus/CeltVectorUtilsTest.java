package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.javax.sonaudio.format.opus.CeltVectorUtils.BlockOrder;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CeltVectorUtilsTest {

    private static float[] randomVector(final int length, final long seed) {
        final var random = new Random(seed);
        final var vector = new float[length];
        for (var index = 0; index < length; index++) {
            vector[index] = (float) random.nextGaussian();
        }
        return vector;
    }

    private static double norm(final float[] vector, final int start, final int length) {
        var sum = 0.0;
        for (var index = start; index < start + length; index++) {
            sum += vector[index] * vector[index];
        }
        return Math.sqrt(sum);
    }

    @Test
    void renormalizingGivesTheVectorTheGainAsItsNorm() {
        final var vector = randomVector(20, 1);

        CeltVectorUtils.renormalize(vector, 4, 12, 2.5f);

        assertThat(norm(vector, 4, 12)).isCloseTo(2.5, within(1e-5));
    }

    @Test
    void renormalizingAZeroVectorLeavesItFinite() {
        final var vector = new float[8];

        CeltVectorUtils.renormalize(vector, 0, 8, 1.0f);

        assertThat(vector).containsOnly(0f);
    }

    @Test
    void scalesPulsesByTheGain() {
        final var vector = new float[5];

        CeltVectorUtils.scalePulses(new int[] {3, -2, 0}, vector, 1, 3, 0.5f);

        assertThat(vector).containsExactly(0f, 1.5f, -1f, 0f, 0f);
    }

    @ParameterizedTest
    @EnumSource(CeltSpread.class)
    void spreadingKeepsTheNormOfASparseBand(final CeltSpread spread) {
        final var vector = new float[48];
        vector[5] = 1f;

        CeltVectorUtils.spreadPulses(vector, 0, 48, 2, 3, spread);

        assertThat(norm(vector, 0, 48)).isCloseTo(1.0, within(1e-5));
    }

    @Test
    void spreadingWithNoSpreadChangesNothing() {
        final var vector = new float[16];
        vector[3] = 1f;

        CeltVectorUtils.spreadPulses(vector, 0, 16, 1, 1, CeltSpread.NONE);

        assertThat(vector[3]).isEqualTo(1f);
    }

    @Test
    void spreadingAMoreDenselyPulsedBandIsLeftAlone() {
        final var vector = new float[16];
        vector[3] = 1f;

        CeltVectorUtils.spreadPulses(vector, 0, 16, 1, 8, CeltSpread.NORMAL);

        assertThat(vector[3]).isEqualTo(1f);
    }

    @Test
    void marksTheBlocksThatHavePulses() {
        final var pulses = new int[] {0, 0, 1, 0, 0, 0, 0, -2};

        assertThat(CeltVectorUtils.collapseMask(pulses, 8, 4)).isEqualTo(0b1010);
        assertThat(CeltVectorUtils.collapseMask(pulses, 8, 1)).isEqualTo(1);
    }

    @Test
    void theHaarTransformUndoesItself() {
        final var original = randomVector(32, 2);
        final var vector = original.clone();

        CeltVectorUtils.transformHaar(vector, 0, 32, 1);
        CeltVectorUtils.transformHaar(vector, 0, 32, 1);

        for (var index = 0; index < 32; index++) {
            assertThat(vector[index]).isCloseTo(original[index], within(1e-5f));
        }
    }

    @Test
    void theHaarTransformPairsEntriesAStrideApart() {
        final var vector = new float[] {1f, 10f, 3f, 20f};

        CeltVectorUtils.transformHaar(vector, 0, 4, 2);

        final var root = (float) Math.sqrt(0.5);
        assertThat(vector[0]).isCloseTo(4 * root, within(1e-6f));
        assertThat(vector[1]).isCloseTo(30 * root, within(1e-6f));
        assertThat(vector[2]).isCloseTo(-2 * root, within(1e-6f));
        assertThat(vector[3]).isCloseTo(-10 * root, within(1e-6f));
    }

    @ParameterizedTest
    @EnumSource(BlockOrder.class)
    void interleavingUndoesDeinterleaving(final BlockOrder order) {
        final var original = randomVector(40, 3);
        final var vector = original.clone();
        final var scratch = new float[40];

        CeltVectorUtils.deinterleaveBlocks(scratch, vector, 0, 5, 8, order);
        CeltVectorUtils.interleaveBlocks(scratch, vector, 0, 5, 8, order);

        assertThat(vector).isEqualTo(original);
    }

    @Test
    void deinterleavingGathersEachBlocksEntries() {
        final var vector = new float[] {0, 10, 1, 11, 2, 12};
        final var scratch = new float[6];

        CeltVectorUtils.deinterleaveBlocks(scratch, vector, 0, 3, 2, BlockOrder.SEQUENTIAL);

        assertThat(vector).containsExactly(0f, 1f, 2f, 10f, 11f, 12f);
    }

    @Test
    void mergingStereoGivesUnitVectorsOfTheSumAndDifference() {
        final var mid = new float[] {0.6f, 0.8f, 0f, 0f};
        final var side = new float[] {0f, 0f, 0.6f, 0.8f};

        CeltVectorUtils.mergeStereo(mid, 0, side, 0, 1.0f, 4);

        assertThat(norm(mid, 0, 4)).isCloseTo(1.0, within(1e-5));
        assertThat(norm(side, 0, 4)).isCloseTo(1.0, within(1e-5));
    }

    @Test
    void mergingStereoOfDegenerateChannelsCopiesTheFirstToTheSecond() {
        final var mid = new float[] {0f, 0f};
        final var side = new float[] {0f, 0f};

        CeltVectorUtils.mergeStereo(mid, 0, side, 0, 0f, 2);

        assertThat(side).containsExactly(0f, 0f);
    }
}
