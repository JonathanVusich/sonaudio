package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SilkStereoMixerTest {

    private static final float TOLERANCE = 1e-6f;
    private static final SilkLayout LAYOUT = new SilkLayout(OpusBandwidth.NARROWBAND, 20);

    private final SilkChannelState mid = new SilkChannelState();
    private final SilkChannelState side = new SilkChannelState();
    private final float[] left = new float[LAYOUT.frameLength()];
    private final float[] right = new float[LAYOUT.frameLength()];

    @Test
    void addsAndSubtractsTheSideChannelFromTheMid() {
        Arrays.fill(mid.output(), 0.5f);
        Arrays.fill(side.output(), 0.1f);
        final var mixer = new SilkStereoMixer();

        mixer.unmix(mid, side, left, right, 0, LAYOUT);

        assertThat(left[10]).isCloseTo(0.6f, within(TOLERANCE));
        assertThat(right[10]).isCloseTo(0.4f, within(TOLERANCE));
    }

    @Test
    void predictsTheSideChannelFromTheLowpassedMidAfterTheWeightsHaveMovedOver() {
        Arrays.fill(mid.output(), 0.5f);
        final var mixer = new SilkStereoMixer();
        mixer.setWeights(0.5f, 0.25f);

        mixer.unmix(mid, side, left, right, 0, LAYOUT);

        // (1 + 0.25) * 0.5 + 0.5 * 0.5 after the 64 samples that narrowband's weights move over.
        assertThat(left[64]).isCloseTo(0.875f, within(TOLERANCE));
        assertThat(right[64]).isCloseTo(0.125f, within(TOLERANCE));
    }

    @Test
    void movesLinearlyFromThePreviousFramesWeightsToTheNewOnesOverTheFirstSamples() {
        Arrays.fill(mid.output(), 0.5f);
        final var mixer = new SilkStereoMixer();
        mixer.setWeights(0, 0.5f);

        mixer.unmix(mid, side, left, right, 0, LAYOUT);

        assertThat(left[0]).isCloseTo(0.5f, within(TOLERANCE));
        assertThat(left[32]).isCloseTo(0.625f, within(TOLERANCE));
        assertThat(left[63]).isCloseTo(0.5f * (1 + 0.5f * 63 / 64), within(TOLERANCE));
        assertThat(left[64]).isCloseTo(0.75f, within(TOLERANCE));
    }

    @Test
    void usesTheWeightsOfThePreviousFrameAsTheStartOfTheNextFramesMove() {
        Arrays.fill(mid.output(), 0.5f);
        final var mixer = new SilkStereoMixer();
        mixer.setWeights(0, 0.5f);
        mixer.unmix(mid, side, left, right, 0, LAYOUT);

        mixer.unmix(mid, side, left, right, 0, LAYOUT);

        assertThat(left[0]).isCloseTo(0.75f, within(TOLERANCE));
    }

    @Test
    void forgetsThePreviousWeightsWhenReset() {
        Arrays.fill(mid.output(), 0.5f);
        final var mixer = new SilkStereoMixer();
        mixer.setWeights(0, 0.5f);
        mixer.unmix(mid, side, left, right, 0, LAYOUT);
        mixer.reset();

        mixer.unmix(mid, side, left, right, 0, LAYOUT);

        assertThat(left[0]).isCloseTo(0.5f, within(TOLERANCE));
    }

    @Test
    void clipsToTheRangeOfASample() {
        Arrays.fill(mid.output(), 0.9f);
        Arrays.fill(side.output(), 0.5f);
        final var mixer = new SilkStereoMixer();

        mixer.unmix(mid, side, left, right, 0, LAYOUT);

        assertThat(left[5]).isEqualTo(1.0f);
        assertThat(right[5]).isCloseTo(0.4f, within(TOLERANCE));
    }

    @Test
    void usesTheSamplesOneEarlierThanTheOnesItIsGiven() {
        // The first sample of the frame uses the one before the frame, which is the history's last.
        mid.output()[SilkChannelState.HISTORY - LAYOUT.frameLength() - 1] = 0.5f;
        final var mixer = new SilkStereoMixer();

        mixer.unmix(mid, side, left, right, 0, LAYOUT);

        assertThat(left[0]).isCloseTo(0.5f, within(TOLERANCE));
        assertThat(left[1]).isCloseTo(0.0f, within(TOLERANCE));
    }

    @Test
    void writesAtTheOffsetItIsGiven() {
        Arrays.fill(mid.output(), 0.5f);
        final var wide = new float[2 * LAYOUT.frameLength()];
        final var mixer = new SilkStereoMixer();

        mixer.unmix(mid, side, wide, new float[wide.length], LAYOUT.frameLength(), LAYOUT);

        assertThat(wide[LAYOUT.frameLength() - 1]).isZero();
        assertThat(wide[LAYOUT.frameLength()]).isCloseTo(0.5f, within(TOLERANCE));
    }
}
