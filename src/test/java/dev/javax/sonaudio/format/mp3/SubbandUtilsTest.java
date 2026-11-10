package dev.javax.sonaudio.format.mp3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class SubbandUtilsTest {

    @Test
    void sampleBelowTheBoundGoesToItsOwnChannel() {
        assertThat(SubbandUtils.sharedChannels(3, 4, 0, 2)).isEqualTo(1);
        assertThat(SubbandUtils.sharedChannels(3, 4, 1, 2)).isEqualTo(2);
    }

    @Test
    void leftSampleFromTheBoundUpGoesToBothChannels() {
        assertThat(SubbandUtils.sharedChannels(4, 4, 0, 2)).isEqualTo(2);
    }

    // MPEG-1 Layer II at 160 kbit/s and 44.1 kHz, in joint stereo with mode extension 2.
    @Test
    void jointStereoBoundComesFromTheModeExtension() {
        final var header = Mp3Fixtures.header("FFFD9064");

        assertThat(SubbandUtils.bound(header, 30)).isEqualTo(12);
        assertThat(SubbandUtils.bound(header, 8)).isEqualTo(8);
    }

    @Test
    void otherModesHaveNoBound() {
        final var header = Mp3Fixtures.header("FFFD9004");

        assertThat(SubbandUtils.bound(header, 30)).isEqualTo(30);
    }

    // Three steps are -2/3, 0 and 2/3, and scale factor 0 doubles them.
    @Test
    void requantizeCentresTheStepsAndScalesThem() {
        assertThat(SubbandUtils.requantize(0, 3, 3)).isCloseTo(-2.0 / 3, within(1e-15));
        assertThat(SubbandUtils.requantize(1, 3, 3)).isZero();
        assertThat(SubbandUtils.requantize(2, 3, 0)).isCloseTo(4.0 / 3, within(1e-15));
    }

    @Test
    void repeatedRowsGivesEachSubbandTheSameClasses() {
        final var classes = new int[] {1, 2};

        final var rows = SubbandUtils.repeatedRows(3, classes);

        assertThat(rows.length).isEqualTo(3);
        for (final var row : rows) {
            assertThat(row).isSameAs(classes);
        }
    }
}
