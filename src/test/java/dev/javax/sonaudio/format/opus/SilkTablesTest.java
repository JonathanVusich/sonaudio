package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Checks the invariants that SILK's tables have by construction, which a mistake in transcribing them would break.
 */
class SilkTablesTest {

    private static final int TOTAL = 256;

    @Test
    void everyModelIsADecreasingInverseCdfThatFallsToZero() {
        for (final var model : allModels()) {
            assertThat(model.length).isGreaterThanOrEqualTo(2);
            assertThat(model[model.length - 1]).isZero();
            assertThat(model[0]).isLessThan(TOTAL);
            for (var index = 1; index < model.length; index++) {
                assertThat(model[index]).isLessThanOrEqualTo(model[index - 1]);
            }
        }
    }

    @Test
    void everyModelHasAsManySymbolsAsItsTableNeeds() {
        assertThat(SilkModels.LSF_S1).hasDimensions(2, 2);
        for (final var narrowAndWide : SilkModels.LSF_S1) {
            for (final var model : narrowAndWide) {
                assertThat(model).hasSize(32);
            }
        }
        assertThat(SilkModels.LSF_S2).hasDimensions(16, 9);
        assertThat(SilkModels.PULSE_COUNT).hasDimensions(11, 18);
        assertThat(SilkModels.GAIN_HIGHBITS).hasDimensions(3, 8);
        assertThat(SilkModels.GAIN_LOWBITS).hasSize(8);
        assertThat(SilkModels.GAIN_DELTA).hasSize(41);
        assertThat(SilkModels.PITCH_HIGHBITS).hasSize(32);
        assertThat(SilkModels.PITCH_DELTA).hasSize(21);
    }

    @Test
    void thePulseLocationModelsSplitAsManyPulsesAsTheyAreGiven() {
        assertThat(SilkModels.PULSE_LOCATION.length).isEqualTo(4);
        for (final var level : SilkModels.PULSE_LOCATION) {
            assertThat(level.length).isEqualTo(16);
            for (var total = 1; total <= 16; total++) {
                assertThat(level[total - 1])
                        .as("%d pulses can fall %d ways", total, total + 1)
                        .hasSize(total + 1);
            }
        }
    }

    @Test
    void theLtpFilterCodebooksHaveAsManyEntriesAsTheirSelectionModelsSymbols() {
        assertThat(SilkTables.LTP_FILTER0_TAPS).hasDimensions(SilkModels.LTP_FILTER0_SEL.length, 5);
        assertThat(SilkTables.LTP_FILTER1_TAPS).hasDimensions(SilkModels.LTP_FILTER1_SEL.length, 5);
        assertThat(SilkTables.LTP_FILTER2_TAPS).hasDimensions(SilkModels.LTP_FILTER2_SEL.length, 5);
    }

    @Test
    void theNlsfCodebooksHaveAnEntryForEveryFirstStageSymbolAndAFrequencyForEveryCoefficient() {
        assertThat(SilkTables.LSF_CODEBOOK_NBMB).hasDimensions(32, 10);
        assertThat(SilkTables.LSF_CODEBOOK_WB).hasDimensions(32, 16);
        assertThat(SilkTables.LSF_WEIGHT_NBMB).hasDimensions(32, 10);
        assertThat(SilkTables.LSF_WEIGHT_WB).hasDimensions(32, 16);
        assertThat(SilkTables.LSF_S2_SEL_NBMB).hasDimensions(32, 10);
        assertThat(SilkTables.LSF_S2_SEL_WB).hasDimensions(32, 16);
        assertThat(SilkTables.LSF_WEIGHT_SEL_NBMB).hasDimensions(32, 9);
        assertThat(SilkTables.LSF_WEIGHT_SEL_WB).hasDimensions(32, 15);
        assertThat(SilkTables.LSF_PRED_WEIGHTS_NBMB).hasDimensions(2, 9);
        assertThat(SilkTables.LSF_PRED_WEIGHTS_WB).hasDimensions(2, 15);
    }

    @Test
    void theSelectionTablesPickExistingModelsAndWeights() {
        assertAllBetween(SilkTables.LSF_S2_SEL_NBMB, SilkModels.LSF_S2.length - 1);
        assertAllBetween(SilkTables.LSF_S2_SEL_WB, SilkModels.LSF_S2.length - 1);
        assertAllBetween(SilkTables.LSF_WEIGHT_SEL_NBMB, 1);
        assertAllBetween(SilkTables.LSF_WEIGHT_SEL_WB, 1);
    }

    @Test
    void theCosineTableFallsFromOneToMinusOneInStepsOfAHundredthOfAHalfCircle() {
        assertThat(SilkTables.COSINE).hasSize(129);
        assertThat(SilkTables.COSINE[0]).isEqualTo(4096);
        assertThat(SilkTables.COSINE[64]).isZero();
        assertThat(SilkTables.COSINE[128]).isEqualTo(-4096);
        for (var index = 1; index < SilkTables.COSINE.length; index++) {
            assertThat(SilkTables.COSINE[index]).isLessThan(SilkTables.COSINE[index - 1]);
            assertThat(SilkTables.COSINE[index]).isEqualTo(-SilkTables.COSINE[128 - index]);
        }
    }

    @Test
    void theFrequencyOrderingsArePermutations() {
        assertThat(SilkTables.LSF_ORDERING_NBMB).containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        assertThat(SilkTables.LSF_ORDERING_WB)
                .containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);
    }

    @Test
    void theMinimumSpacingsLeaveRoomForEveryFrequency() {
        assertThat(SilkTables.LSF_MIN_SPACING_NBMB).hasSize(11);
        assertThat(SilkTables.LSF_MIN_SPACING_WB).hasSize(17);
        assertThat(Arrays.stream(SilkTables.LSF_MIN_SPACING_NBMB).sum()).isLessThan(32768);
        assertThat(Arrays.stream(SilkTables.LSF_MIN_SPACING_WB).sum()).isLessThan(32768);
    }

    @Test
    void theStereoWeightsRiseAndAreSymmetricAboutZero() {
        assertThat(SilkTables.STEREO_WEIGHTS).hasSize(16);
        for (var index = 1; index < 16; index++) {
            assertThat(SilkTables.STEREO_WEIGHTS[index]).isGreaterThan(SilkTables.STEREO_WEIGHTS[index - 1]);
            assertThat(SilkTables.STEREO_WEIGHTS[index]).isEqualTo(-SilkTables.STEREO_WEIGHTS[15 - index]);
        }
    }

    @Test
    void thePitchTablesAgreeOnTheLagRangeOfEachBandwidth() {
        assertThat(SilkTables.PITCH_MIN_LAG).containsExactly(16, 24, 32);
        assertThat(SilkTables.PITCH_MAX_LAG).containsExactly(144, 216, 288);
        assertThat(SilkTables.PITCH_SCALE).containsExactly(4, 6, 8);
        assertThat(SilkTables.PITCH_OFFSET_NB10MS).hasDimensions(3, 2);
        assertThat(SilkTables.PITCH_OFFSET_NB20MS).hasDimensions(11, 4);
        assertThat(SilkTables.PITCH_OFFSET_MBWB10MS).hasDimensions(12, 2);
        assertThat(SilkTables.PITCH_OFFSET_MBWB20MS).hasDimensions(34, 4);
        assertThat(SilkModels.PITCH_CONTOUR_NB10MS).hasSize(3);
        assertThat(SilkModels.PITCH_CONTOUR_NB20MS).hasSize(11);
        assertThat(SilkModels.PITCH_CONTOUR_MBWB10MS).hasSize(12);
        assertThat(SilkModels.PITCH_CONTOUR_MBWB20MS).hasSize(34);
    }

    @Test
    void theExcitationTablesHaveABlockCountForEveryBandwidthAndFrameLength() {
        assertThat(SilkTables.SHELL_BLOCKS).hasDimensions(3, 2);
        assertThat(SilkTables.SHELL_BLOCKS[0]).containsExactly(5, 10);
        assertThat(SilkTables.SHELL_BLOCKS[1]).containsExactly(8, 15);
        assertThat(SilkTables.SHELL_BLOCKS[2]).containsExactly(10, 20);
        assertThat(SilkTables.QUANT_OFFSET).hasDimensions(2, 2);
        assertThat(SilkModels.EXCITATION_SIGN.length).isEqualTo(3);
        assertThat(SilkTables.STEREO_INTERP_LEN).containsExactly(64, 96, 128);
    }

    private void assertAllBetween(final int[][] table, final int highest) {
        for (final var row : table) {
            assertThat(Arrays.stream(row).min().getAsInt()).isGreaterThanOrEqualTo(0);
            assertThat(Arrays.stream(row).max().getAsInt()).isLessThanOrEqualTo(highest);
        }
    }

    private List<int[]> allModels() {
        final var models = new ArrayList<int[]>();
        models.add(SilkModels.LBRR_FLAGS_40);
        models.add(SilkModels.LBRR_FLAGS_60);
        models.add(SilkModels.STEREO_S1);
        models.add(SilkModels.STEREO_S2);
        models.add(SilkModels.STEREO_S3);
        models.add(SilkModels.MID_ONLY);
        models.add(SilkModels.FRAME_TYPE_INACTIVE);
        models.add(SilkModels.FRAME_TYPE_ACTIVE);
        models.addAll(Arrays.asList(SilkModels.GAIN_HIGHBITS));
        models.add(SilkModels.GAIN_LOWBITS);
        models.add(SilkModels.GAIN_DELTA);
        for (final var narrowAndWide : SilkModels.LSF_S1) {
            models.addAll(Arrays.asList(narrowAndWide));
        }
        models.addAll(Arrays.asList(SilkModels.LSF_S2));
        models.add(SilkModels.LSF_S2_EXT);
        models.add(SilkModels.LSF_INTERPOLATION_OFFSET);
        models.add(SilkModels.PITCH_HIGHBITS);
        models.add(SilkModels.PITCH_LOWBITS_MB);
        models.add(SilkModels.PITCH_DELTA);
        models.add(SilkModels.PITCH_CONTOUR_NB10MS);
        models.add(SilkModels.PITCH_CONTOUR_NB20MS);
        models.add(SilkModels.PITCH_CONTOUR_MBWB10MS);
        models.add(SilkModels.PITCH_CONTOUR_MBWB20MS);
        models.add(SilkModels.LTP_FILTER);
        models.add(SilkModels.LTP_FILTER0_SEL);
        models.add(SilkModels.LTP_FILTER1_SEL);
        models.add(SilkModels.LTP_FILTER2_SEL);
        models.add(SilkModels.LTP_SCALE_INDEX);
        models.add(SilkModels.LCG_SEED);
        models.addAll(Arrays.asList(SilkModels.EXC_RATE));
        models.addAll(Arrays.asList(SilkModels.PULSE_COUNT));
        for (final var level : SilkModels.PULSE_LOCATION) {
            models.addAll(Arrays.asList(level));
        }
        models.add(SilkModels.EXCITATION_LSB);
        for (final var type : SilkModels.EXCITATION_SIGN) {
            for (final var offset : type) {
                models.addAll(Arrays.asList(offset));
            }
        }
        return models;
    }
}
