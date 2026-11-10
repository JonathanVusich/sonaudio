package dev.javax.sonaudio.format.mp3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class AliasCoefficientsTest {

    // ISO/IEC 11172-3 Table B.9.
    private static final double[] CI = {-0.6, -0.535, -0.33, -0.185, -0.095, -0.041, -0.0142, -0.0037};

    // The coefficients are the spec's, rounded to floats.
    @Test
    void matchesSpec() {
        for (var butterfly = 0; butterfly < AliasCoefficients.COUNT; butterfly++) {
            final var ci = CI[butterfly];
            final var norm = Math.sqrt(1 + ci * ci);

            final double cs = AliasCoefficients.cs(butterfly);
            final double ca = AliasCoefficients.ca(butterfly);

            assertThat(cs).isCloseTo(1 / norm, within(1e-7));
            assertThat(ca).isCloseTo(ci / norm, within(1e-7));
        }
    }
}
