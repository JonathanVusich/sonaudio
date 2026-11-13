package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CeltFrameTest {

    private static CeltFrame frame(final int sizeShift, final int channels, final CeltBlockMode blockMode) {
        return new CeltFrame(sizeShift, channels, 0, 21, 1000, blockMode);
    }

    @Test
    void hasAsManySamplesAsItsSizeInShortBlocks() {
        assertThat(frame(0, 1, CeltBlockMode.LONG).frameSamples()).isEqualTo(120);
        assertThat(frame(3, 1, CeltBlockMode.LONG).frameSamples()).isEqualTo(960);
    }

    @Test
    void isOneBlockUnlessItIsTransient() {
        assertThat(frame(3, 1, CeltBlockMode.LONG).blocks()).isEqualTo(1);
        assertThat(frame(3, 1, CeltBlockMode.SHORT).blocks()).isEqualTo(8);
    }

    @Test
    void scalesABandsFirstCoefficientsAndWidthsToTheFrameSize() {
        final var frame = frame(2, 1, CeltBlockMode.LONG);

        assertThat(frame.bandStart(8)).isEqualTo(32);
        assertThat(frame.bandSize(8)).isEqualTo(8);
    }

    @Test
    void scalesBitFiguresToTheChannelsAndSize() {
        assertThat(frame(0, 1, CeltBlockMode.LONG).scaleToFrame(400)).isEqualTo(100);
        assertThat(frame(3, 2, CeltBlockMode.LONG).scaleToFrame(400)).isEqualTo(1600);
    }
}
