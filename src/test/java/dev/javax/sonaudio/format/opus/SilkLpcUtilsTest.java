package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SilkLpcUtilsTest {

    @Test
    void roundsAProductShiftedRightToTheNearest() {
        // 5 * 3 = 15 is 3.75 as Q2 and 1.875 as Q3.
        assertThat(SilkLpcUtils.roundedProduct(5, 3, 2)).isEqualTo(4);
        assertThat(SilkLpcUtils.roundedProduct(5, 3, 3)).isEqualTo(2);
        assertThat(SilkLpcUtils.roundedProduct(-5, 3, 2)).isEqualTo(-4);
    }

    @Test
    void roundsHalvesUp() {
        assertThat(SilkLpcUtils.roundedProduct(1, 1, 1)).isEqualTo(1);
        assertThat(SilkLpcUtils.roundedProduct(-1, 1, 1)).isZero();
    }

    @Test
    void doesNotOverflowTheProductOfTwoLargeNumbers() {
        assertThat(SilkLpcUtils.roundedProduct(Integer.MAX_VALUE, Integer.MAX_VALUE, 31))
                .isEqualTo(2147483646L);
    }

    @Test
    void takesTheHighHalfOfASignedProduct() {
        assertThat(SilkLpcUtils.highProduct(1 << 30, 1 << 30)).isEqualTo(1 << 28);
        assertThat(SilkLpcUtils.highProduct(-(1 << 30), 1 << 30)).isEqualTo(-(1 << 28));
        assertThat(SilkLpcUtils.highProduct(3, 3)).isZero();
        assertThat(SilkLpcUtils.highProduct(-3, 3)).isEqualTo(-1);
    }

    @Test
    void interpolatesTheCosineTableLinearly() {
        // The table is cosines in Q12: times 256 and down by 8 makes the Q17 of twice the cosine, rounded.
        assertThat(SilkLpcUtils.cosine(0)).isEqualTo(SilkTables.COSINE[0] * 256 + 4 >> 3);
        final var halfway = SilkLpcUtils.cosine(128);
        final var between = (SilkTables.COSINE[0] * 256 + (SilkTables.COSINE[1] - SilkTables.COSINE[0]) * 128 + 4) >> 3;
        assertThat(halfway).isEqualTo(between);
        assertThat(SilkLpcUtils.cosine(256)).isEqualTo((SilkTables.COSINE[1] * 256 + 4) >> 3);
    }

    @Test
    void isZeroAtAQuarterOfTheCircle() {
        assertThat(SilkLpcUtils.cosine(16384)).isZero();
    }

    @Test
    void ordersFrequenciesByTheTableOfTheOrder() {
        assertThat(SilkLpcUtils.ordering(10, 1)).isEqualTo(SilkTables.LSF_ORDERING_NBMB[1]);
        assertThat(SilkLpcUtils.ordering(16, 1)).isEqualTo(SilkTables.LSF_ORDERING_WB[1]);
    }
}
