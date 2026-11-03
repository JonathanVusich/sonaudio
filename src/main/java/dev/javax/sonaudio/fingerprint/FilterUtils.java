package dev.javax.sonaudio.fingerprint;

import lombok.experimental.UtilityClass;

/**
 * How Chromaprint's filters compare the two parts of their rectangle.
 */
@UtilityClass
class FilterUtils {

    /**
     * @return how much the first sum of energy outweighs the second, on a log scale, which is 0 when they're equal
     */
    static double contrast(final double first, final double second) {
        return Math.log(1 + first) - Math.log(1 + second);
    }
}
