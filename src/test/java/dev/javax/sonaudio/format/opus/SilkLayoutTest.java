package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SilkLayoutTest {

    @ParameterizedTest
    @CsvSource({"10, 1, 2", "20, 1, 4", "40, 2, 4", "60, 3, 4"})
    void cutsAnOpusFrameIntoSilkFramesOfTenOrTwentyMilliseconds(
            final int frameMillis, final int frameCount, final int subframes) {
        final var layout = new SilkLayout(OpusBandwidth.WIDEBAND, frameMillis);

        assertThat(layout.frameCount()).isEqualTo(frameCount);
        assertThat(layout.subframes()).isEqualTo(subframes);
    }

    @ParameterizedTest
    @CsvSource({"NARROWBAND, 40, 10", "MEDIUMBAND, 60, 10", "WIDEBAND, 80, 16"})
    void sizesSubframesAndTheFilterByBandwidth(
            final OpusBandwidth bandwidth, final int subframeLength, final int order) {
        final var layout = new SilkLayout(bandwidth, 20);

        assertThat(layout.subframeLength()).isEqualTo(subframeLength);
        assertThat(layout.frameLength()).isEqualTo(4 * subframeLength);
        assertThat(layout.order()).isEqualTo(order);
    }

    @ParameterizedTest
    @CsvSource({"NARROWBAND, 10, 80", "MEDIUMBAND, 10, 120", "WIDEBAND, 10, 160"})
    void aTenMillisecondFrameHasHalfAsManySamples(
            final OpusBandwidth bandwidth, final int frameMillis, final int frameLength) {
        assertThat(new SilkLayout(bandwidth, frameMillis).frameLength()).isEqualTo(frameLength);
    }
}
