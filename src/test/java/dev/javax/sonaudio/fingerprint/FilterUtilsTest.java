package dev.javax.sonaudio.fingerprint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class FilterUtilsTest {

    @Test
    void contrastIsZeroForEqualParts() {
        assertThat(FilterUtils.contrast(3, 3)).isZero();
    }

    @Test
    void contrastComparesOnALogScale() {
        assertThat(FilterUtils.contrast(Math.E - 1, 0)).isCloseTo(1, within(1e-15));
        assertThat(FilterUtils.contrast(0, Math.E - 1)).isCloseTo(-1, within(1e-15));
    }
}
