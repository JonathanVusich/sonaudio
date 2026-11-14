package dev.javax.sonaudio.fingerprint;

import static org.assertj.core.api.Assertions.assertThat;

import dev.javax.sonaudio.format.IntPcmFrame;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

class SignalUtilsTest {

    // Audio of 16 bits or less is scaled up to 16, and wider audio to 32, before it's divided by its full scale.
    @Test
    void toFloatDividesBySixteenOrThirtyTwoBitFullScale() {
        assertThat(SignalUtils.toFloat(16384, 16)).isEqualTo(0.5f);
        assertThat(SignalUtils.toFloat(64, 8)).isEqualTo(0.5f);
        assertThat(SignalUtils.toFloat(1 << 22, 24)).isEqualTo(0.5f);
        assertThat(SignalUtils.toFloat(-32768, 16)).isEqualTo(-1f);
    }

    @Test
    void mixTakesHalfOfEachStereoChannel() {
        final var frame = new IntPcmFrame(new int[][] {{16384}, {0}}, 44100);

        assertThat(SignalUtils.mix(frame, 0, 16)).isEqualTo(0.25f);
    }

    @Test
    void mixGivesMonoAsItIs() {
        final var frame = new IntPcmFrame(new int[][] {{-16384}}, 44100);

        assertThat(SignalUtils.mix(frame, 0, 16)).isEqualTo(-0.5f);
    }

    @Test
    void mixAveragesMoreChannels() {
        final var frame = new IntPcmFrame(new int[][] {{8192}, {16384}, {24576}}, 44100);

        assertThat(SignalUtils.mix(frame, 0, 16)).isEqualTo(0.5f);
    }

    // libswresample's integer mix: each channel times 16384, plus 16384, then down 15 bits.
    @Test
    void integerMonoMixesStereoInIntegers() {
        assertThat(SignalUtils.integerMono(new float[][] {{0.5f}, {0.25f}}, 0)).isEqualTo(12288);
        assertThat(SignalUtils.integerMono(new float[][] {{0.5f}}, 0)).isEqualTo(16384);
    }

    @Test
    void mixedDownIsTheOneChannelItself() {
        final var channels = new float[][] {{0.1f, 0.2f}};

        assertThat(SignalUtils.mixedDown(channels, 2)).isSameAs(channels[0]);
    }

    @Test
    void mixedDownMixesEachSample() {
        assertThat(SignalUtils.mixedDown(new float[][] {{1, 0, 9}, {0, 1, 9}}, 2))
                .containsExactly(0.5f, 0.5f);
    }

    @Test
    void mixChannelsAveragesMoreThanTwo() {
        assertThat(SignalUtils.mixChannels(new float[][] {{3}, {6}, {9}}, 0)).isEqualTo(6f);
    }

    @Test
    void sixChannelsAreMixedWithLibswresamplesFiveOneWeights() {
        final var channels = new float[][] {{1}, {1}, {1}, {100}, {1}, {1}};

        assertThat(SignalUtils.mixedDownFromVorbisLayout(channels, 1)).containsExactly(1f);
    }

    @Test
    void theCentreOfFiveOneCountsMoreThanAFrontChannel() {
        final var centre = SignalUtils.mixedDownFromVorbisLayout(new float[][] {{0}, {0}, {1}, {0}, {0}, {0}}, 1);
        final var front = SignalUtils.mixedDownFromVorbisLayout(new float[][] {{1}, {0}, {0}, {0}, {0}, {0}}, 1);

        assertThat(centre[0]).isCloseTo(0.292893f, Offset.offset(1e-6f));
        assertThat(front[0]).isCloseTo(0.207107f, Offset.offset(1e-6f));
    }

    @Test
    void toSixteenBitsRoundsAndClips() {
        assertThat(SignalUtils.toSixteenBits(new float[] {0.5f, 2f, -2f, 0.25f}, 3))
                .containsExactly(16384, 32767, -32768);
    }

    // Mono is resampled first only when it's raised, and more channels always are.
    @Test
    void resamplesFirstWhenMixingDownOrRaisingTheRate() {
        assertThat(SignalUtils.resamplesFirst(1, 44100)).isFalse();
        assertThat(SignalUtils.resamplesFirst(1, 8000)).isTrue();
        assertThat(SignalUtils.resamplesFirst(2, 44100)).isTrue();
    }
}
