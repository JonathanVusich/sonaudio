package dev.javax.sonaudio.format.alac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.javax.sonaudio.exception.InvalidFormatException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SampleTableTest {

    // Two chunks of 2 samples then one of 1, with sizes 10 to 50, a duration of 1000 for the first 3 samples and 500
    // for the next one.
    private static SampleTable table(final int[] firstChunks, final int[] chunkSamples, final long... offsets) {
        final var descriptions = new int[firstChunks.length];
        Arrays.fill(descriptions, 1);
        return new SampleTable(
                new long[] {3, 1},
                new long[] {1000, 500},
                firstChunks,
                chunkSamples,
                descriptions,
                0,
                5,
                new int[] {10, 20, 30, 40, 50},
                offsets);
    }

    @Test
    void laysSamplesOutChunkByChunk() throws InvalidFormatException {
        final var index =
                table(new int[] {1, 3}, new int[] {2, 1}, 100, 200, 300).index();

        assertThat(index.count()).isEqualTo(5);
        assertThat(index.offset(0)).isEqualTo(100);
        assertThat(index.offset(1)).isEqualTo(110);
        assertThat(index.offset(2)).isEqualTo(200);
        assertThat(index.offset(3)).isEqualTo(230);
        assertThat(index.offset(4)).isEqualTo(300);
        assertThat(index.size(3)).isEqualTo(40);
    }

    @Test
    void timesSamplesByTheirDurations() throws InvalidFormatException {
        final var index =
                table(new int[] {1, 3}, new int[] {2, 1}, 100, 200, 300).index();

        assertThat(index.timestamp(0)).isZero();
        assertThat(index.timestamp(3)).isEqualTo(3000);
        assertThat(index.timestamp(4)).isEqualTo(3500);
        assertThat(index.duration(3)).isEqualTo(500);
    }

    @Test
    void countsTheTimeAndTheSamplesItCovers() {
        final var table = table(new int[] {1}, new int[] {2}, 100, 200, 300);

        assertThat(table.totalDuration()).isEqualTo(3500);
        assertThat(table.timedSamples()).isEqualTo(4);
    }

    @Test
    void repeatsTheLastDurationForSamplesTheTimeTableDoesNotCover() throws InvalidFormatException {
        final var index = table(new int[] {1}, new int[] {5}, 100).index();

        assertThat(index.duration(4)).isEqualTo(500);
        assertThat(index.timestamp(4)).isEqualTo(3500);
    }

    @Test
    void usesTheSizeEverySampleHasWhenThereIsOne() throws InvalidFormatException {
        final var table = new SampleTable(
                new long[] {2},
                new long[] {10},
                new int[] {1},
                new int[] {2},
                new int[] {1},
                7,
                2,
                new int[0],
                new long[] {50});

        final var index = table.index();

        assertThat(index.offset(1)).isEqualTo(57);
        assertThat(index.size(1)).isEqualTo(7);
    }

    @Test
    void findsTheSampleAtOrBeforeATime() throws InvalidFormatException {
        final var index =
                table(new int[] {1, 3}, new int[] {2, 1}, 100, 200, 300).index();

        assertThat(index.lastStartingAtOrBefore(0)).isZero();
        assertThat(index.lastStartingAtOrBefore(999)).isZero();
        assertThat(index.lastStartingAtOrBefore(1000)).isEqualTo(1);
        assertThat(index.lastStartingAtOrBefore(3499)).isEqualTo(3);
        assertThat(index.lastStartingAtOrBefore(1_000_000)).isEqualTo(4);
    }

    @Test
    void refusesChunksThatHoldMoreSamplesThanHaveSizes() {
        final var table = table(new int[] {1}, new int[] {3}, 100, 200);

        assertThatThrownBy(table::index)
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("more samples");
    }

    @Test
    void refusesChunkEntriesThatDoNotIncrease() {
        final var table = table(new int[] {1, 1}, new int[] {2, 1}, 100, 200);

        assertThatThrownBy(table::index)
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("stsc");
    }

    @Test
    void refusesChunkEntriesBeyondTheChunks() {
        final var table = table(new int[] {1, 5}, new int[] {2, 1}, 100, 200);

        assertThatThrownBy(table::index)
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("stco");
    }

    @Test
    void refusesATableWithoutChunks() {
        final var table = table(new int[] {1}, new int[] {2});

        assertThatThrownBy(table::index)
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("missing");
    }
}
