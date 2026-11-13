package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SilkChannelStateTest {

    private static final int FRAME_LENGTH = 80;

    @Test
    void startsEmptyAndSilent() {
        final var state = new SilkChannelState();

        assertThat(state.history()).isEqualTo(SilkHistory.EMPTY);
        assertThat(state.output()).containsOnly(0f);
        assertThat(state.previousSignalType()).isEqualTo(SilkSignalType.INACTIVE);
    }

    @Test
    void movesTheHistoryOnPastADecodedFrame() {
        final var state = new SilkChannelState();
        state.output()[SilkChannelState.HISTORY + FRAME_LENGTH - 1] = 0.25f;
        state.lpcHistory()[SilkChannelState.HISTORY + FRAME_LENGTH - 1] = 0.5f;

        state.finishFrame(FRAME_LENGTH, SilkSignalType.VOICED);

        // The frame's last sample is now the last of the history, which is the sample before the next frame.
        assertThat(state.output()[SilkChannelState.HISTORY - 1]).isEqualTo(0.25f);
        assertThat(state.lpcHistory()[SilkChannelState.HISTORY - 1]).isEqualTo(0.5f);
        assertThat(state.history()).isEqualTo(SilkHistory.DECODED);
        assertThat(state.previousSignalType()).isEqualTo(SilkSignalType.VOICED);
    }

    @Test
    void forgetsEverythingWhenResetAfterADecodedFrame() {
        final var state = new SilkChannelState();
        state.output()[10] = 1f;
        state.nlsf()[3] = 99;
        state.lpc()[2] = 0.5f;
        state.setLogGain(20);
        state.setPrimaryLag(100);
        state.finishFrame(FRAME_LENGTH, SilkSignalType.UNVOICED);

        state.reset();

        assertThat(state.history()).isEqualTo(SilkHistory.EMPTY);
        assertThat(state.output()).containsOnly(0f);
        assertThat(state.nlsf()).containsOnly(0);
        assertThat(state.lpc()).containsOnly(0f);
        assertThat(state.logGain()).isZero();
        assertThat(state.primaryLag()).isZero();
        assertThat(state.previousSignalType()).isEqualTo(SilkSignalType.INACTIVE);
    }

    // FFmpeg only resets a channel that has decoded a frame, so what a partly read frame left in it stays.
    @Test
    void keepsWhatAPartlyReadFrameLeftWhenResetBeforeAnyFrameIsDecoded() {
        final var state = new SilkChannelState();
        state.setLogGain(20);
        state.nlsf()[0] = 1234;

        state.reset();

        assertThat(state.logGain()).isEqualTo(20);
        assertThat(state.nlsf()[0]).isEqualTo(1234);
    }
}
