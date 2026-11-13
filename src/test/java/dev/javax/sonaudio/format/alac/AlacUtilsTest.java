package dev.javax.sonaudio.format.alac;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AlacUtilsTest {

    @ParameterizedTest
    @CsvSource({
        "0x7FFF, 16, 32767",
        "0x8000, 16, -32768",
        "0x12345, 16, 9029",
        "0xFFFF, 16, -1",
        "0x80000, 20, -524288",
        "0x7FFFFF, 24, 8388607",
        "0x800000, 24, -8388608",
        "0xFFFFFFFF, 32, -1",
        "0x80000000, 32, -2147483648",
        "1, 1, -1",
        "2, 1, 0"
    })
    void signExtendsTheLowBits(final String value, final int bits, final int expected) {
        assertThat(AlacUtils.signExtend(Long.decode(value).intValue(), bits)).isEqualTo(expected);
    }

    @Test
    void signExtendsA32BitValueWithoutChangingIt() {
        assertThat(AlacUtils.signExtend(Integer.MIN_VALUE, 32)).isEqualTo(Integer.MIN_VALUE);
        assertThat(AlacUtils.signExtend(Integer.MAX_VALUE, 32)).isEqualTo(Integer.MAX_VALUE);
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "1, 0", "2, 1", "3, 1", "4, 2", "127, 6", "128, 7", "130, 7", "65535, 15"})
    void findsTheHighestSetBit(final int value, final int expected) {
        assertThat(AlacUtils.floorLog2(value)).isEqualTo(expected);
    }

    @Test
    void findsTheHighestBitOfAValueThatIsNegativeAsASignedNumber() {
        assertThat(AlacUtils.floorLog2(-1)).isEqualTo(31);
        assertThat(AlacUtils.floorLog2(Integer.MIN_VALUE)).isEqualTo(31);
    }

    @ParameterizedTest
    @CsvSource({
        "10, 44100, 44100, 10",
        "22050, 1, 2, 11025",
        "1, 1, 2, 1",
        "1, 1, 3, 0",
        "2, 1, 3, 1",
        "-1, 1, 2, -1",
        "-1, 1, 3, 0",
        "-2, 1, 3, -1",
        "0, 5, 7, 0",
        "1000, 192000, 44100, 4354"
    })
    void roundsRescaledValuesToNearestWithHalvesAwayFromZero(
            final long value, final long numerator, final long denominator, final long expected) {
        assertThat(AlacUtils.rescale(value, numerator, denominator)).isEqualTo(expected);
    }

    @Test
    void rescalesWithoutOverflowing() {
        assertThat(AlacUtils.rescale(Long.MAX_VALUE / 2, 1_000_000, 1_000_000)).isEqualTo(Long.MAX_VALUE / 2);
        assertThat(AlacUtils.rescale(1L << 50, 192000, 192000)).isEqualTo(1L << 50);
    }
}
