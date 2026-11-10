package dev.javax.sonaudio.format.mp3;

import lombok.experimental.UtilityClass;

/**
 * Lays out a {@link HuffmanCode}'s values.
 */
@UtilityClass
class HuffmanUtils {

    /**
     * @param values the values in the order of their codewords
     * @param lengths how many bits each value's codeword takes, in the same order
     * @return how many bits each value's codeword takes, by value, and 0 for numbers that aren't values
     */
    static int[] valueCodeLengths(final int[] values, final int[] lengths) {
        var maxValue = 0;
        for (final var value : values) {
            maxValue = Math.max(maxValue, value);
        }
        final var codeLengths = new int[maxValue + 1];
        for (var index = 0; index < values.length; index++) {
            codeLengths[values[index]] = lengths[index];
        }
        return codeLengths;
    }
}
