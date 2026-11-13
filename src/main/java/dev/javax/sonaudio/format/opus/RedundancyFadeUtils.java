package dev.javax.sonaudio.format.opus;

import lombok.experimental.UtilityClass;

/**
 * The cross-fades that join a 5 ms redundancy frame to the frame beside it (RFC 6716 section 4.5.1.4): over 120
 * samples, the one fades out as the other fades in, by the square of CELT's power-complementary window.
 */
@UtilityClass
class RedundancyFadeUtils {

    /**
     * How many samples a fade lasts, which is the CELT overlap.
     */
    static final int FADE_SAMPLES = 120;

    private static final float[] WINDOW_SQUARED = windowSquared();

    private static float[] windowSquared() {
        final var window = new float[FADE_SAMPLES];
        for (var index = 0; index < FADE_SAMPLES; index++) {
            final var inner = Math.sin(Math.PI * (index + 0.5) / (2 * FADE_SAMPLES));
            final var weight = Math.sin(Math.PI / 2 * inner * inner);
            window[index] = (float) (weight * weight);
        }
        return window;
    }

    /**
     * Makes each sample of the target {@code fadingIn * w + fadingOut * (1 - w)}, where {@code w} rises from the window's
     * first sample to its last, as FFmpeg's {@code opus_fade} does. The target may be one of the sources.
     *
     * @param target where to write the samples
     * @param targetOffset the index of the first
     * @param fadingOut the samples that are faded out, which go with {@code 1 - w}
     * @param fadingOutOffset the index of the first
     * @param fadingIn the samples that are faded in, which go with {@code w}
     * @param fadingInOffset the index of the first
     * @param windowOffset the index in the window of the first sample
     * @param length how many samples
     */
    static void crossFade(
            final float[] target,
            final int targetOffset,
            final float[] fadingOut,
            final int fadingOutOffset,
            final float[] fadingIn,
            final int fadingInOffset,
            final int windowOffset,
            final int length) {
        for (var index = 0; index < length; index++) {
            // As in C, the fade in is a float product, and the fade out a double one.
            final var weight = WINDOW_SQUARED[windowOffset + index];
            final var faded = fadingIn[fadingInOffset + index] * weight;
            target[targetOffset + index] = (float) (faded + fadingOut[fadingOutOffset + index] * (1.0 - weight));
        }
    }
}
