package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CeltPulseUtilsTest {

    // Entries of the table FFmpeg lists: U(n, k) for the first and last of some rows.
    @ParameterizedTest
    @CsvSource({
        "2, 2, 3",
        "2, 176, 351",
        "3, 3, 13",
        "4, 4, 63",
        "5, 5, 321",
        "6, 6, 1683",
        "7, 7, 8989",
        "8, 8, 48639",
        "10, 10, 1462563",
        "13, 16, 2653649025",
        "14, 14, 1409933619"
    })
    void countsPulsePlacementsAsFfmpegTabulatesThem(final int dimensions, final int pulses, final long expected) {
        assertThat(CeltPulseUtils.pulseCount(dimensions, pulses)).isEqualTo(expected);
        assertThat(CeltPulseUtils.pulseCount(pulses, dimensions)).isEqualTo(expected);
    }

    @Test
    void everyIndexNamesADifferentVectorWithTheRightNumberOfPulses() {
        for (var dimensions = 2; dimensions <= 6; dimensions++) {
            for (var pulses = 1; pulses <= 7; pulses++) {
                assertEveryIndexIsADistinctVector(dimensions, pulses);
            }
        }
    }

    private void assertEveryIndexIsADistinctVector(final int dimensions, final int pulses) {
        final var count = CeltPulseUtils.vectorCount(dimensions, pulses);
        final var seen = new HashSet<List<Integer>>();
        final var vector = new int[dimensions];
        for (var index = 0L; index < count; index++) {
            final var norm =
                    CeltPulseUtils.decodeVector(dimensions, pulses, index, vector, new CeltPulseUtils.PulseCursor());
            assertThat(Arrays.stream(vector).map(Math::abs).sum()).isEqualTo(pulses);
            assertThat(Arrays.stream(vector)
                            .mapToLong(value -> (long) value * value)
                            .sum())
                    .isEqualTo(norm);
            seen.add(Arrays.stream(vector).boxed().toList());
        }
        assertThat(seen).hasSize((int) count);
    }

    // The index of a vector, which the encoder computes: the sum, over the entries from the last to the first, of the
    // vectors that come before it.
    private static long indexOf(final int[] vector) {
        var index = 0L;
        var sum = 0;
        for (var position = vector.length - 1; position >= 0; position--) {
            final var dimensions = vector.length - position;
            final var magnitude = Math.abs(vector[position]);
            index += CeltPulseUtils.pulseCount(dimensions, sum);
            if (vector[position] < 0) {
                index += CeltPulseUtils.pulseCount(dimensions, sum + magnitude + 1);
            }
            sum += magnitude;
        }
        return index;
    }

    @Test
    void decodesIndicesOfLargerVectorsToTheVectorsThatHaveThatIndex() {
        final var random = new java.util.Random(7);
        for (var dimensions = 3; dimensions <= 14; dimensions++) {
            for (var pulses = 1; pulses <= 128; pulses++) {
                final var count = CeltPulseUtils.vectorCount(dimensions, pulses);
                if (count <= 0 || count >= 1L << 32) {
                    continue;
                }
                final var index = Math.floorMod(random.nextLong(), count);
                final var vector = new int[dimensions];
                CeltPulseUtils.decodeVector(dimensions, pulses, index, vector, new CeltPulseUtils.PulseCursor());
                assertThat(indexOf(vector))
                        .as("%d dimensions, %d pulses", dimensions, pulses)
                        .isEqualTo(index);
            }
        }
    }

    @Test
    void countsVectorsOfAFewPulses() {
        // Two dimensions with one pulse: (+-1, 0) and (0, +-1).
        assertThat(CeltPulseUtils.vectorCount(2, 1)).isEqualTo(4);
        // Three dimensions with two pulses: 6 of one pulse twice (1 position * 2 signs * 3) plus 12 of two places.
        assertThat(CeltPulseUtils.vectorCount(3, 2)).isEqualTo(18);
    }

    @Test
    void choosesThePulseCountWhoseCostIsClosestToTheBits() {
        // The cache of band size 1 at the shortest frame: cost of each pulse count is 7, 15, ...
        final var cacheStart = CeltCacheTables.INDEX[8];
        assertThat(CeltPulseUtils.pulsesForBits(cacheStart, 0)).isZero();
        assertThat(CeltPulseUtils.bitsForPulses(cacheStart, 0)).isZero();
        final var pulses = CeltPulseUtils.pulsesForBits(cacheStart, 1000);
        assertThat(pulses).isEqualTo(CeltCacheTables.BITS[cacheStart]);
    }

    @Test
    void scalesPulseIndicesToPulseCounts() {
        assertThat(CeltPulseUtils.pulsesOfIndex(5)).isEqualTo(5);
        assertThat(CeltPulseUtils.pulsesOfIndex(8)).isEqualTo(8);
        assertThat(CeltPulseUtils.pulsesOfIndex(15)).isEqualTo(15);
        assertThat(CeltPulseUtils.pulsesOfIndex(16)).isEqualTo(16);
        assertThat(CeltPulseUtils.pulsesOfIndex(17)).isEqualTo(18);
    }

    @Test
    void approximatesTheCosineOfAnAngleInQuarterTurnsAsFixedPoint() {
        for (var angle = 64; angle < 16384; angle += 64) {
            final var expected = Math.cos(Math.PI / 2 * angle / 16384) * 32768;
            assertThat((double) CeltPulseUtils.fixedPointCosine(angle)).isBetween(expected - 5, expected + 5);
        }
    }

    @Test
    void approximatesTheLogarithmOfTheTangent() {
        final var angle = 6000;
        final var sine = CeltPulseUtils.fixedPointCosine(16384 - angle);
        final var cosine = CeltPulseUtils.fixedPointCosine(angle);
        final var expected = Math.log(Math.tan(Math.PI / 2 * angle / 16384)) / Math.log(2) * 2048;
        assertThat((double) CeltPulseUtils.logTangent(sine, cosine)).isBetween(expected - 40, expected + 40);
    }
}
