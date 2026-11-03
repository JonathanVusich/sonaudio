package dev.javax.sonaudio.format.mp3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class ImdctUtilsTest {

    @Test
    void mixedBlockTransformsItsTwoLowestSubbandsWithTheNormalWindow() {
        assertThat(ImdctUtils.subbandBlockType(0, BlockType.SHORT, LowSubbands.NORMAL_WINDOW))
                .isEqualTo(BlockType.NORMAL);
        assertThat(ImdctUtils.subbandBlockType(1, BlockType.SHORT, LowSubbands.NORMAL_WINDOW))
                .isEqualTo(BlockType.NORMAL);
        assertThat(ImdctUtils.subbandBlockType(2, BlockType.SHORT, LowSubbands.NORMAL_WINDOW))
                .isEqualTo(BlockType.SHORT);
    }

    @Test
    void otherBlocksTransformEverySubbandWithTheirBlockType() {
        assertThat(ImdctUtils.subbandBlockType(0, BlockType.SHORT, LowSubbands.BY_BLOCK_TYPE))
                .isEqualTo(BlockType.SHORT);
        assertThat(ImdctUtils.subbandBlockType(0, BlockType.START, LowSubbands.BY_BLOCK_TYPE))
                .isEqualTo(BlockType.START);
    }

    @Test
    void startWindowIsTheNormalOneThenFlatThenAShortOnesEndThenZero() {
        assertThat(ImdctUtils.startWindow(5, 0.3)).isEqualTo(0.3);
        assertThat(ImdctUtils.startWindow(20, 0.3)).isEqualTo(1);
        assertThat(ImdctUtils.startWindow(24, 0.3)).isCloseTo(Math.sin(Math.PI / 12 * 6.5), within(1e-15));
        assertThat(ImdctUtils.startWindow(35, 0.3)).isZero();
    }

    // The stop window is the start window reversed, as the normal window is symmetric.
    @Test
    void stopWindowIsTheStartWindowReversed() {
        for (var sample = 0; sample < 36; sample++) {
            final var mirrored = 35 - sample;
            final var start = ImdctUtils.startWindow(sample, normalWindow(sample));
            final var stop = ImdctUtils.stopWindow(mirrored, normalWindow(mirrored));
            assertThat(stop).isCloseTo(start, within(1e-15));
        }
    }

    private static double normalWindow(final int sample) {
        return Math.sin(Math.PI / 36 * (sample + 0.5));
    }
}
