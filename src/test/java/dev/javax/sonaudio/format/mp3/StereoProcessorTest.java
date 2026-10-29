package dev.javax.sonaudio.format.mp3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import dev.javax.sonaudio.exception.InvalidFormatException;

class StereoProcessorTest {

    private static final float PRECISION = 1e-6f;
    // MPEG-1 joint stereo at 44.1 kHz, with mid/side or intensity stereo.
    private static final String MID_SIDE_HEADER = "FFFB9060";
    private static final String INTENSITY_HEADER = "FFFB9050";
    private static final int LONG_FACTORS = 21;
    private static final int LAST_BAND = 20;

    private final StereoProcessor processor = new StereoProcessor();

    // FFmpeg takes the 1/sqrt(2) into the global gain when no band is intensity coded.
    @Test
    void decodesMidAndSideWithoutScaling() throws IOException, InvalidFormatException {
        final var left = spectrum(3);
        final var right = spectrum(1);

        processStereo(MID_SIDE_HEADER, left, right, new int[LONG_FACTORS]);

        assertThat(left.line(0)).isEqualTo(4);
        assertThat(right.line(0)).isEqualTo(2);
    }

    // Above the right channel's last line that isn't 0, position 0 puts all of the left channel's value in the right
    // channel, 6 all in the left, and 3 half in each.
    @Test
    void splitsIntensityBandsByPosition() throws IOException, InvalidFormatException {
        assertThat(intensitySplit(0)).containsExactly(0, 2);
        assertThat(intensitySplit(6)).containsExactly(new float[] {2, 0}, within(PRECISION));
        assertThat(intensitySplit(3)).containsExactly(new float[] {1, 1}, within(PRECISION));
    }

    // Position 7 says the band isn't intensity coded, so it's left as it's coded.
    @Test
    void leavesIllegalPositionUncoded() throws IOException, InvalidFormatException {
        assertThat(intensitySplit(7)).containsExactly(2, 0);
    }

    // The right channel's line in the band below stops intensity coding there.
    @Test
    void leavesCodedBandsAlone() throws IOException, InvalidFormatException {
        final var left = spectrum(2);
        final var right = spectrum(1);

        processStereo(INTENSITY_HEADER, left, right, new int[LONG_FACTORS]);

        assertThat(left.line(0)).isEqualTo(2);
        assertThat(right.line(0)).isEqualTo(1);
    }

    // In MPEG-2, an odd position scales the left channel by a step, and an even one the right. An even
    // scalefac_compress makes the step 2^(-1/4).
    @Test
    void scalesMpeg2IntensityBySteps() {
        final var version = MpegVersion.MPEG_2;

        assertThat(IntensityRatios.leftRatio(version, 0, 0)).isEqualTo(1);
        assertThat(IntensityRatios.rightRatio(version, 0, 0)).isEqualTo(1);
        assertThat(IntensityRatios.leftRatio(version, 0, 3)).isCloseTo((float) Math.pow(2, -0.5), within(PRECISION));
        assertThat(IntensityRatios.rightRatio(version, 0, 3)).isEqualTo(1);
        assertThat(IntensityRatios.leftRatio(version, 0, 4)).isEqualTo(1);
        assertThat(IntensityRatios.rightRatio(version, 0, 4)).isCloseTo((float) Math.pow(2, -0.5), within(PRECISION));
    }

    // The left channel has 2 in the last long band's first line, and the right channel is all 0.
    private float[] intensitySplit(final int position) throws IOException, InvalidFormatException {
        final var line = ScaleFactorBands.HZ_44100.longBandStart(LAST_BAND);
        final var left = new Spectrum();
        left.setLine(line, 2);
        left.setNonZeroEnd(Spectrum.LINES);
        final var right = new Spectrum();
        final var positions = new int[LONG_FACTORS];
        positions[LAST_BAND] = position;

        processStereo(INTENSITY_HEADER, left, right, positions);

        return new float[] {left.line(line), right.line(line)};
    }

    // The right channel is a long block, whose scale factors are the intensity positions.
    private void processStereo(final String header, final Spectrum left, final Spectrum right, final int[] positions)
            throws IOException, InvalidFormatException {
        final var factors = new int[ScaleFactors.SIZE];
        System.arraycopy(positions, 0, factors, 0, positions.length);
        final var rightFactors = new ScaleFactors(factors, 0);

        processor.processStereo(Mp3Fixtures.header(header), left, right, Mp3Fixtures.longChannel(), rightFactors);
    }

    private static Spectrum spectrum(final float firstLine) {
        final var spectrum = new Spectrum();
        spectrum.setLine(0, firstLine);
        spectrum.setNonZeroEnd(1);
        return spectrum;
    }
}
