package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CeltMathUtilsTest {

    @Test
    void keepsAValueWithinTheRange() {
        assertThat(CeltMathUtils.clip(5, 0, 10)).isEqualTo(5);
    }

    @Test
    void limitsAValueToTheEndsOfTheRange() {
        assertThat(CeltMathUtils.clip(-3, 0, 10)).isZero();
        assertThat(CeltMathUtils.clip(30, 0, 10)).isEqualTo(10);
    }

    @Test
    void givesTheEndOfARangeThatEndsBelowItsStartForAValueAboveIt() {
        assertThat(CeltMathUtils.clip(2, 0, -4)).isEqualTo(-4);
        assertThat(CeltMathUtils.clip(-9, 0, -4)).isZero();
    }
}
