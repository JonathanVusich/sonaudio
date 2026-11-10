package dev.javax.sonaudio.format.mp3;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HuffmanUtilsTest {

    @Test
    void valueCodeLengthsOrdersTheLengthsByValue() {
        assertThat(HuffmanUtils.valueCodeLengths(new int[] {2, 0, 1}, new int[] {1, 2, 2}))
                .containsExactly(2, 2, 1);
    }

    @Test
    void valueCodeLengthsLeavesNumbersThatAreNoValueAtZero() {
        assertThat(HuffmanUtils.valueCodeLengths(new int[] {3, 0}, new int[] {1, 1}))
                .containsExactly(1, 0, 0, 1);
    }
}
