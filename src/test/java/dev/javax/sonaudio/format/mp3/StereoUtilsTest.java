package dev.javax.sonaudio.format.mp3;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StereoUtilsTest {

    private final ScaleFactorBands bands = ScaleFactorBands.fromSampleRate(44100);
    private final Spectrum right = new Spectrum();

    @Test
    void intensityCodingStartsAtLongBandZeroWhenNoneHasAValue() {
        assertThat(StereoUtils.firstIntensityLongBand(bands, right, 21)).isZero();
    }

    @Test
    void intensityCodingStartsAboveTheLastLongBandWithAValue() {
        right.setLine(bands.longBandStart(5), 1);
        right.setLine(bands.longBandStart(2) + 1, -1);

        assertThat(StereoUtils.firstIntensityLongBand(bands, right, 21)).isEqualTo(6);
    }

    @Test
    void longBandsAboveTheLayoutsDontCount() {
        right.setLine(bands.longBandStart(10), 1);

        assertThat(StereoUtils.firstIntensityLongBand(bands, right, 8)).isZero();
    }

    @Test
    void intensityCodingStartsAtTheFirstShortBandWhenNoneHasAValue() {
        assertThat(StereoUtils.firstIntensityShortBand(bands, right, 3, 0)).isEqualTo(3);
    }

    // Each window has its own last band with a value.
    @Test
    void intensityCodingStartsAboveTheWindowsLastShortBandWithAValue() {
        right.setLine(StereoUtils.shortLineStart(bands, 7, 1), 1);

        assertThat(StereoUtils.firstIntensityShortBand(bands, right, 0, 1)).isEqualTo(8);
        assertThat(StereoUtils.firstIntensityShortBand(bands, right, 0, 0)).isZero();
        assertThat(StereoUtils.firstIntensityShortBand(bands, right, 0, 2)).isZero();
    }

    // At 44.1 kHz, short band 0 is 4 lines wide, so each window's part of it is 4 lines, one after another.
    @Test
    void shortLineStartPutsEachBandsWindowsOneAfterAnother() {
        assertThat(StereoUtils.shortLineStart(bands, 0, 0)).isZero();
        assertThat(StereoUtils.shortLineStart(bands, 0, 1)).isEqualTo(4);
        assertThat(StereoUtils.shortLineStart(bands, 1, 0)).isEqualTo(12);
    }
}
