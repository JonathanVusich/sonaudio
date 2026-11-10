package dev.javax.sonaudio.fingerprint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class ResamplerUtilsTest {

    private static final float[] SAMPLES = {1, 2, 3, 4};

    @Test
    void extendedSampleIsTheSampleWithinTheAudio() {
        assertThat(ResamplerUtils.extendedSample(SAMPLES, 4, 0)).isEqualTo(1);
        assertThat(ResamplerUtils.extendedSample(SAMPLES, 4, 3)).isEqualTo(4);
    }

    // Before the start, sample -n is sample n, and after the end, sample length + n is sample length - 1 - n.
    @Test
    void extendedSampleMirrorsTheStartAndReflectsTheEnd() {
        assertThat(ResamplerUtils.extendedSample(SAMPLES, 4, -1)).isEqualTo(2);
        assertThat(ResamplerUtils.extendedSample(SAMPLES, 4, -3)).isEqualTo(4);
        assertThat(ResamplerUtils.extendedSample(SAMPLES, 4, 4)).isEqualTo(4);
        assertThat(ResamplerUtils.extendedSample(SAMPLES, 4, 6)).isEqualTo(2);
    }

    @Test
    void extendedSampleIsSilentPastTheMirrorAndReflection() {
        assertThat(ResamplerUtils.extendedSample(SAMPLES, 4, -4)).isZero();
        assertThat(ResamplerUtils.extendedSample(SAMPLES, 4, 8)).isZero();
    }

    @Test
    void sincIsOneAtZero() {
        assertThat(ResamplerUtils.sinc(0, 0, 0.5)).isEqualTo(1);
        assertThat(ResamplerUtils.sinc(0, 0.7, 1.0)).isEqualTo(1);
    }

    @Test
    void sincIsSineOverItsArgument() {
        assertThat(ResamplerUtils.sinc(Math.PI / 2, 0, 0.5)).isCloseTo(2 / Math.PI, within(1e-15));
    }

    // A filter that doesn't narrow the band takes each tap's sine as given.
    @Test
    void sincTakesTheSineGivenWhenTheBandIsWhole() {
        assertThat(ResamplerUtils.sinc(2, 0.5, 1.0)).isEqualTo(0.25);
    }

    @Test
    void exactPhaseCountIsTheOutputRateOverTheRatesGreatestCommonDivisor() {
        assertThat(ResamplerUtils.exactPhaseCount(44100, 11025, 256)).isEqualTo(1);
        assertThat(ResamplerUtils.exactPhaseCount(48000, 11025, 256)).isEqualTo(147);
    }

    @Test
    void exactPhaseCountIsAtMostTheMostAskedFor() {
        assertThat(ResamplerUtils.exactPhaseCount(44101, 11025, 256)).isEqualTo(256);
    }

    @Test
    void greatestCommonDivisorDividesBoth() {
        assertThat(ResamplerUtils.greatestCommonDivisor(48000, 11025)).isEqualTo(75);
        assertThat(ResamplerUtils.greatestCommonDivisor(11025, 48000)).isEqualTo(75);
        assertThat(ResamplerUtils.greatestCommonDivisor(7, 0)).isEqualTo(7);
    }

    @Test
    void besselI0MatchesItsSeries() {
        assertThat(ResamplerUtils.besselI0(0)).isEqualTo(1);
        assertThat(ResamplerUtils.besselI0(1)).isCloseTo(1.2660658777520082, within(1e-14));
        assertThat(ResamplerUtils.besselI0(9)).isCloseTo(1093.5883545113745, within(1e-10));
    }

    @Test
    void evaluatePolynomialStartsFromTheConstantTerm() {
        assertThat(ResamplerUtils.evaluatePolynomial(new double[] {1, 2, 3}, 2)).isEqualTo(17);
    }
}
