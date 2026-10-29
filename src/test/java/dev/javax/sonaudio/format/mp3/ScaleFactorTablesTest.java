package dev.javax.sonaudio.format.mp3;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScaleFactorTablesTest {

    // Each table's partitions hold every band of the layout: 21 long bands, 12 short bands of three windows, or 6 long
    // bands below 9 short ones.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void lsfPartitionsHoldEveryBand(final int table) {
        assertThat(bandCount(table, BlockLayout.LONG)).isEqualTo(21);
        assertThat(bandCount(table, BlockLayout.SHORT)).isEqualTo(36);
        assertThat(bandCount(table, BlockLayout.MIXED)).isEqualTo(33);
    }

    private static int bandCount(final int table, final BlockLayout layout) {
        return IntStream.range(0, ScaleFactorTables.LSF_PARTITIONS)
                .map(partition -> ScaleFactorTables.lsfBandCount(table, layout, partition))
                .sum();
    }
}
