package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class CeltTablesTest {

    @Test
    void bandWidthsAreTheDifferencesOfTheBandEdges() {
        for (var band = 0; band < CeltTables.BAND_COUNT; band++) {
            assertThat(CeltTables.BAND_WIDTH[band])
                    .isEqualTo(CeltTables.BAND_START[band + 1] - CeltTables.BAND_START[band]);
        }
    }

    @Test
    void theBandsEndAtTheLastBinOfALongFrame() {
        assertThat(CeltTables.BAND_START[CeltTables.BAND_COUNT]).isEqualTo(100);
    }

    @Test
    void theStaticAllocationRisesFromOneVectorToTheNext() {
        for (var vector = 1; vector < CeltTables.STATIC_ALLOC.length; vector++) {
            for (var band = 0; band < CeltTables.BAND_COUNT; band++) {
                assertThat(CeltTables.STATIC_ALLOC[vector][band])
                        .isGreaterThanOrEqualTo(CeltTables.STATIC_ALLOC[vector - 1][band]);
            }
        }
    }

    @Test
    void theInverseCdfsFallToZeroAtTheirLastSymbol() {
        assertThat(CeltTables.SPREAD_ICDF[CeltTables.SPREAD_ICDF.length - 1]).isZero();
        assertThat(CeltTables.ALLOCATION_TRIM_ICDF[CeltTables.ALLOCATION_TRIM_ICDF.length - 1])
                .isZero();
        assertThat(CeltTables.TAPSET_ICDF[CeltTables.TAPSET_ICDF.length - 1]).isZero();
    }

    @Test
    void theInverseCdfsStartBelowTheirTotals() {
        assertThat(CeltTables.SPREAD_ICDF[0]).isLessThan(1 << CeltTables.SPREAD_BITS);
        assertThat(CeltTables.ALLOCATION_TRIM_ICDF[0]).isLessThan(1 << CeltTables.ALLOCATION_TRIM_BITS);
        assertThat(CeltTables.TAPSET_ICDF[0]).isLessThan(1 << CeltTables.TAPSET_BITS);
    }

    @Test
    void theWindowIsPowerComplementary() {
        final var length = CeltWindowTables.LENGTH;
        for (var index = 0; index < length / 2; index++) {
            final var rising = CeltWindowTables.WINDOW[index];
            final var falling = CeltWindowTables.WINDOW[length - 1 - index];
            assertThat((double) rising * rising + (double) falling * falling).isCloseTo(1.0, within(1e-6));
        }
    }

    @Test
    void theSquaredWindowIsTheWindowSquared() {
        for (var index = 0; index < CeltWindowTables.LENGTH; index++) {
            final var window = CeltWindowTables.WINDOW[index];
            assertThat((double) CeltWindowTables.WINDOW_SQUARED[index])
                    .isCloseTo((double) window * window, within(1e-5));
        }
    }

    @Test
    void everyCostCacheRisesWithItsPulseCount() {
        for (final var start : CeltCacheTables.INDEX) {
            if (start < 0) {
                continue;
            }
            final var largest = CeltCacheTables.BITS[start];
            for (var pulses = 2; pulses <= largest; pulses++) {
                assertThat(CeltCacheTables.BITS[start + pulses])
                        .isGreaterThanOrEqualTo(CeltCacheTables.BITS[start + pulses - 1]);
            }
        }
    }

    @Test
    void theTapsOfEachToneSetSumToAboutOne() {
        for (final var taps : CeltTables.POSTFILTER_TAPS) {
            assertThat((double) taps[0] + 2 * taps[1] + 2 * taps[2]).isCloseTo(1.0, within(1e-3));
        }
    }
}
