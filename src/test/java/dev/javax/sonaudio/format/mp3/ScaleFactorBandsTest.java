package dev.javax.sonaudio.format.mp3;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import dev.javax.sonaudio.exception.InvalidFormatException;

class ScaleFactorBandsTest {

    // A granule has 576 lines: one long block of them, or three short windows of 192.
    @ParameterizedTest
    @EnumSource(ScaleFactorBands.class)
    void bandsCoverEveryLine(final ScaleFactorBands bands) {
        final var longLines = IntStream.range(0, ScaleFactorBands.LONG_BANDS)
                .map(bands::longBandWidth)
                .sum();
        final var shortLines = IntStream.range(0, ScaleFactorBands.SHORT_BANDS)
                .map(bands::shortBandWidth)
                .sum();

        assertThat(longLines).isEqualTo(576);
        assertThat(shortLines).isEqualTo(192);
    }

    @ParameterizedTest
    @EnumSource(MpegVersion.class)
    void hasBandsForEverySampleRate(final MpegVersion version) throws InvalidFormatException {
        for (var index = 0; index < 3; index++) {
            final var sampleRate = version.sampleRate(index);

            assertThat(ScaleFactorBands.fromSampleRate(sampleRate).name()).isEqualTo("HZ_" + sampleRate);
        }
    }

    // ISO/IEC 11172-3 Table B.8: at 44.1 kHz, long band 20 starts at line 342 and is 76 lines wide.
    @Test
    void matchesSpecAt44100Hz() {
        final var bands = ScaleFactorBands.HZ_44100;
        final var start = IntStream.range(0, 20).map(bands::longBandWidth).sum();

        assertThat(start).isEqualTo(342);
        assertThat(bands.longBandWidth(20)).isEqualTo(76);
    }
}
