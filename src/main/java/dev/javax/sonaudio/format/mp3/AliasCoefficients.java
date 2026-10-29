package dev.javax.sonaudio.format.mp3;

import java.util.Arrays;
import lombok.experimental.UtilityClass;

/**
 * The butterflies alias reduction applies between neighbouring subbands of long blocks: cs and ca, worked out from the
 * coefficients ci in ISO/IEC 11172-3 Annex B, Table B.9 as {@code cs = 1 / sqrt(1 + ci^2)} and
 * {@code ca = ci / sqrt(1 + ci^2)}.
 */
@UtilityClass
final class AliasCoefficients {

    /**
     * How many butterflies each pair of subbands takes.
     */
    static final int COUNT = 8;

    private static final double[] CI = {-0.6, -0.535, -0.33, -0.185, -0.095, -0.041, -0.0142, -0.0037};
    private static final double[] CS =
            Arrays.stream(CI).map(ci -> 1 / Math.sqrt(1 + ci * ci)).toArray();
    private static final double[] CA =
            Arrays.stream(CI).map(ci -> ci / Math.sqrt(1 + ci * ci)).toArray();

    // The same as 32-bit floats, as FFmpeg's decoder holds them.
    private static final float[] FLOAT_CS = {
        8.574929237365722656e-01f,
        8.817420005798339844e-01f,
        9.496286511421203613e-01f,
        9.833145737648010254e-01f,
        9.955177903175354004e-01f,
        9.991605877876281738e-01f,
        9.998992085456848145e-01f,
        9.999931454658508301e-01f
    };
    private static final float[] FLOAT_CA = {
        -5.144957900047302246e-01f,
        -4.717319905757904053e-01f,
        -3.133774697780609131e-01f,
        -1.819131970405578613e-01f,
        -9.457419067621231079e-02f,
        -4.096558317542076111e-02f,
        -1.419856864959001541e-02f,
        -3.699974622577428818e-03f
    };

    /**
     * @param butterfly from 0 to {@link #COUNT} - 1
     */
    static float floatCs(final int butterfly) {
        return FLOAT_CS[butterfly];
    }

    /**
     * @param butterfly from 0 to {@link #COUNT} - 1
     */
    static float floatCa(final int butterfly) {
        return FLOAT_CA[butterfly];
    }

    /**
     * @param butterfly from 0 to {@link #COUNT} - 1
     */
    static double cs(final int butterfly) {
        return CS[butterfly];
    }

    /**
     * @param butterfly from 0 to {@link #COUNT} - 1
     */
    static double ca(final int butterfly) {
        return CA[butterfly];
    }
}
