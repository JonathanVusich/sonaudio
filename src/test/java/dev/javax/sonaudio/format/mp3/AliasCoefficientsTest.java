package dev.javax.sonaudio.format.mp3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class AliasCoefficientsTest {

    // Each butterfly is a rotation, so it keeps the energy of the two lines it mixes.
    @Test
    void butterfliesAreRotations() {
        for (var butterfly = 0; butterfly < AliasCoefficients.COUNT; butterfly++) {
            final var cs = AliasCoefficients.cs(butterfly);
            final var ca = AliasCoefficients.ca(butterfly);

            assertThat(cs * cs + ca * ca).isCloseTo(1, within(1e-12));
        }
    }

    // ISO/IEC 11172-3 Table B.9 gives ci[0] as -0.6, and ca / cs is ci.
    @Test
    void matchesSpec() {
        assertThat(AliasCoefficients.ca(0) / AliasCoefficients.cs(0)).isCloseTo(-0.6, within(1e-12));
        assertThat(AliasCoefficients.cs(0)).isCloseTo(1 / Math.sqrt(1.36), within(1e-12));
    }
}
