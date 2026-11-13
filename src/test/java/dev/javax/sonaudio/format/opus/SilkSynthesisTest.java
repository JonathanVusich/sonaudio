package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SilkSynthesisTest {

    private static final SilkLayout LAYOUT = new SilkLayout(OpusBandwidth.NARROWBAND, 10);
    private static final int FRAME_LENGTH = LAYOUT.frameLength();
    private static final int HISTORY = SilkChannelState.HISTORY;
    private static final int LTP_TAPS = 5;
    private static final float TOLERANCE = 1e-6f;

    private final SilkChannelState state = new SilkChannelState();
    private final SilkSynthesis synthesis = new SilkSynthesis();

    @Test
    void scalesTheExcitationByTheSubframesGainWithoutAFilter() {
        final var excitation = new float[FRAME_LENGTH];
        Arrays.fill(excitation, 0.25f);

        synthesis.synthesise(state, LAYOUT, unvoiced(new float[] {1.0f, 2.0f}, silentFilter()), excitation);

        assertThat(outputOfLastFrame(0)).isEqualTo(0.25f);
        assertThat(outputOfLastFrame(39)).isEqualTo(0.25f);
        assertThat(outputOfLastFrame(40)).isEqualTo(0.5f);
        assertThat(outputOfLastFrame(79)).isEqualTo(0.5f);
    }

    @Test
    void clipsTheOutputButNotTheFilterState() {
        final var excitation = new float[FRAME_LENGTH];
        excitation[0] = 3.0f;

        synthesis.synthesise(state, LAYOUT, unvoiced(new float[] {1.0f, 1.0f}, silentFilter()), excitation);

        assertThat(outputOfLastFrame(0)).isEqualTo(1.0f);
        assertThat(state.lpcHistory()[HISTORY - FRAME_LENGTH]).isEqualTo(3.0f);
    }

    @Test
    void feedsTheFilteredOutputBackThroughTheFilter() {
        final var excitation = new float[FRAME_LENGTH];
        excitation[0] = 1.0f;
        final var body = new float[SilkLpcUtils.MAX_ORDER];
        body[0] = 0.5f;

        synthesis.synthesise(state, LAYOUT, unvoiced(new float[] {1.0f, 1.0f}, filter(body)), excitation);

        assertThat(outputOfLastFrame(0)).isEqualTo(1.0f);
        assertThat(outputOfLastFrame(1)).isEqualTo(0.5f);
        assertThat(outputOfLastFrame(2)).isEqualTo(0.25f);
        assertThat(outputOfLastFrame(3)).isEqualTo(0.125f);
    }

    @Test
    void continuesTheFilterFromTheLastFramesState() {
        final var impulse = new float[FRAME_LENGTH];
        impulse[FRAME_LENGTH - 1] = 1.0f;
        final var body = new float[SilkLpcUtils.MAX_ORDER];
        body[0] = 0.5f;
        final var sideInfo = unvoiced(new float[] {1.0f, 1.0f}, filter(body));
        synthesis.synthesise(state, LAYOUT, sideInfo, impulse);

        synthesis.synthesise(state, LAYOUT, sideInfo, new float[FRAME_LENGTH]);

        assertThat(outputOfLastFrame(0)).isEqualTo(0.5f);
        assertThat(outputOfLastFrame(1)).isEqualTo(0.25f);
        assertThat(state.history()).isEqualTo(SilkHistory.DECODED);
    }

    @Test
    void repeatsAVoicedFramesExcitationAtThePitchLag() {
        final var excitation = new float[FRAME_LENGTH];
        excitation[0] = 0.5f;
        final var taps = new float[][] {{0, 0, 1.0f, 0, 0}, {0, 0, 1.0f, 0, 0}};

        synthesis.synthesise(state, LAYOUT, voiced(new int[] {20, 20}, taps), excitation);

        for (var index = 0; index < FRAME_LENGTH; index++) {
            assertThat(outputOfLastFrame(index)).as("sample %d", index).isCloseTo(pulseAt(index), within(TOLERANCE));
        }
        assertThat(state.previousSignalType()).isEqualTo(SilkSignalType.VOICED);
    }

    private float pulseAt(final int index) {
        if (index % 20 == 0) {
            return 0.5f;
        }
        return 0.0f;
    }

    private float outputOfLastFrame(final int index) {
        return state.output()[HISTORY - FRAME_LENGTH + index];
    }

    private SilkLpc silentFilter() {
        return filter(new float[SilkLpcUtils.MAX_ORDER]);
    }

    private SilkLpc filter(final float[] body) {
        return new SilkLpc(body, new float[SilkLpcUtils.MAX_ORDER], SilkLpc.LeadIn.ABSENT, 4);
    }

    private SilkSideInfo unvoiced(final float[] gains, final SilkLpc lpc) {
        return new SilkSideInfo(
                SilkSignalType.UNVOICED, QuantizationOffset.LOW, gains, lpc, new int[2], new float[2][LTP_TAPS], 1.0f);
    }

    private SilkSideInfo voiced(final int[] pitchLags, final float[][] taps) {
        return new SilkSideInfo(
                SilkSignalType.VOICED,
                QuantizationOffset.LOW,
                new float[] {1.0f, 1.0f},
                silentFilter(),
                pitchLags,
                taps,
                1.0f);
    }
}
