package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CeltEnergyTest {

    private static CeltFrame frame(final int startBand, final int endBand, final BlockMode blockMode) {
        return new CeltFrame(3, 1, startBand, endBand, 1000, blockMode);
    }

    private static RangeDecoder decoderOf(final long seed) {
        final var frame = new byte[125];
        new Random(seed).nextBytes(frame);
        final var decoder = new RangeDecoder();
        decoder.initialise(frame, 0, frame.length);
        return decoder;
    }

    @Test
    void startsWithNothingPredictedAndSilenceBeforeIt() {
        final var energy = new CeltEnergy();

        assertThat(energy.energy(0, 4)).isZero();
        assertThat(energy.previousEnergy(0, 0, 4)).isEqualTo(CeltEnergy.SILENCE);
        assertThat(energy.previousEnergy(1, 1, 20)).isEqualTo(CeltEnergy.SILENCE);
    }

    @Test
    void scalesEachBandByTwoToItsEnergyAndMean() {
        final var energy = new CeltEnergy();
        energy.decodeCoarse(decoderOf(1), frame(0, 21, BlockMode.LONG));
        final var coefficients = new float[960];
        Arrays.fill(coefficients, 1f);
        final var frame = frame(0, 21, BlockMode.LONG);

        energy.denormalize(0, frame, coefficients);

        for (var band = 0; band < 21; band++) {
            final var expected = Math.pow(2, energy.energy(0, band) + CeltTables.MEAN_ENERGY[band]);
            assertThat((double) coefficients[frame.bandStart(band)]).isCloseTo(expected, within(expected * 1e-5));
        }
    }

    @Test
    void leavesTheBandsOutsideTheFrameAlone() {
        final var energy = new CeltEnergy();
        final var coefficients = new float[960];
        Arrays.fill(coefficients, 1f);
        final var frame = frame(17, 21, BlockMode.LONG);
        energy.decodeCoarse(decoderOf(2), frame);

        energy.denormalize(0, frame, coefficients);

        assertThat(coefficients[0]).isEqualTo(1f);
        assertThat(coefficients[frame.bandStart(16)]).isEqualTo(1f);
    }

    @Test
    void codesNoEnergyInTheBandsOutsideTheFrame() {
        final var energy = new CeltEnergy();

        energy.decodeCoarse(decoderOf(3), frame(17, 21, BlockMode.LONG));

        assertThat(energy.energy(0, 5)).isZero();
        assertThat(energy.energy(0, 19)).isNotZero();
    }

    @Test
    void remembersTheLastTwoFramesAfterLongBlocks() {
        final var energy = new CeltEnergy();
        final var frame = frame(0, 21, BlockMode.LONG);
        energy.decodeCoarse(decoderOf(4), frame);
        final var first = energy.energy(0, 3);
        energy.finishFrame(frame, 1);
        energy.decodeCoarse(decoderOf(5), frame);
        final var second = energy.energy(0, 3);
        energy.finishFrame(frame, 1);

        assertThat(energy.previousEnergy(0, 0, 3)).isEqualTo(second);
        assertThat(energy.previousEnergy(0, 1, 3)).isEqualTo(first);
    }

    @Test
    void lowersTheLastFramesEnergyAfterShortBlocks() {
        final var energy = new CeltEnergy();
        final var longFrame = frame(0, 21, BlockMode.LONG);
        energy.decodeCoarse(decoderOf(6), longFrame);
        energy.finishFrame(longFrame, 1);
        final var before = energy.previousEnergy(0, 0, 3);
        final var shortFrame = frame(0, 21, BlockMode.SHORT);
        energy.decodeCoarse(decoderOf(7), shortFrame);
        final var current = energy.energy(0, 3);

        energy.finishFrame(shortFrame, 1);

        assertThat(energy.previousEnergy(0, 0, 3)).isEqualTo(Math.min(before, current));
    }

    @Test
    void marksTheBandsAFrameDidNotCodeAsSilentForTheNextFrame() {
        final var energy = new CeltEnergy();
        final var frame = frame(17, 21, BlockMode.LONG);
        energy.decodeCoarse(decoderOf(8), frame);

        energy.finishFrame(frame, 1);

        assertThat(energy.previousEnergy(0, 0, 2)).isEqualTo(CeltEnergy.SILENCE);
        assertThat(energy.previousEnergy(0, 0, 18)).isNotEqualTo(CeltEnergy.SILENCE);
    }

    @Test
    void aMonoFrameGivesTheSecondChannelItsEnergies() {
        final var energy = new CeltEnergy();
        final var frame = frame(0, 21, BlockMode.LONG);
        energy.decodeCoarse(decoderOf(9), frame);

        energy.finishFrame(frame, 1);

        assertThat(energy.previousEnergy(1, 0, 6)).isEqualTo(energy.previousEnergy(0, 0, 6));
    }

    @Test
    void forgetsEverythingOnReset() {
        final var energy = new CeltEnergy();
        final var frame = frame(0, 21, BlockMode.LONG);
        energy.decodeCoarse(decoderOf(10), frame);
        energy.finishFrame(frame, 1);

        energy.reset();

        assertThat(energy.previousEnergy(0, 0, 6)).isEqualTo(CeltEnergy.SILENCE);
    }
}
