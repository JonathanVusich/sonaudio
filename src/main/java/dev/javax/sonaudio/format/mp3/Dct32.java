package dev.javax.sonaudio.format.mp3;

/**
 * The 32-point DCT of the polyphase synthesis filterbank, in the order of operations of the AVX version of FFmpeg's
 * {@code dct32_float} (libavcodec/x86/dct32.asm), so that it gives the same floats: five passes of butterflies on eight
 * lanes at once, then a last pass one float at a time. Each instruction of the assembly is a step on eight-lane
 * registers. The decoder keeps one, which only holds the registers.
 */
final class Dct32 {

    private static final int LANES = 8;
    private static final int HALF = LANES / 2;
    private static final int SIZE = 32;
    private static final int NEGATE_PAIRS = 0b11001100;

    private static final float[] COSINES_0 = {
        0.500603f, 0.505471f, 0.515447f, 0.531043f, 0.553104f, 0.582935f, 0.622504f, 0.674808f
    };
    private static final float[] COSINES_1 = {
        -10.190008f, -3.407609f, -2.057781f, -1.484165f, -1.169440f, -0.972568f, -0.839350f, -0.744536f
    };
    private static final float[] COSINES_2 = {
        0.502419f, 0.522499f, 0.566944f, 0.646822f, 0.788155f, 1.060678f, 1.722447f, 5.101149f
    };
    private static final float[] COSINES_3 = {
        0.509796f, 0.601345f, 0.899976f, 2.562916f, 0.509796f, 0.601345f, 0.899976f, 2.562916f
    };
    private static final float[] COSINES_4 = {
        1.000000f, 1.000000f, 1.306563f, 0.541196f, 1.000000f, 1.000000f, 1.306563f, 0.541196f
    };
    private static final float[] COSINES_5 = {
        1.000000f, 0.707107f, 1.000000f, -0.707107f, 1.000000f, 0.707107f, 1.000000f, -0.707107f
    };

    private final float[][] m = new float[LANES][LANES];
    private final float[] permuted = new float[LANES];
    private final float[] original = new float[LANES];

    /**
     * @param output where the 32 transformed values go, from {@code outputStart}
     * @param input the 32 subband samples, from {@code inputStart}
     */
    void transform(final float[] output, final int outputStart, final float[] input, final int inputStart) {
        loadFirstHalf(input, inputStart);
        butterfly(4, 5, COSINES_0, 6);
        loadSecondHalf(input, inputStart);
        butterfly(2, 6, COSINES_1, 0);
        butterfly(5, 6, COSINES_2, 0);
        butterfly(4, 2, COSINES_2, 7);
        thirdPass();
        reverseButterfly(5, COSINES_4, 0x1b);
        reverseButterfly(4, COSINES_4, 0x1b);
        reverseButterfly(1, COSINES_4, 0x1b);
        reverseButterfly(3, COSINES_4, 0x1b);
        reverseButterfly(5, COSINES_5, 0xb1);
        reverseButterfly(4, COSINES_5, 0xb1);
        reverseButterfly(1, COSINES_5, 0xb1);
        reverseButterfly(3, COSINES_5, 0xb1);
        storePasses(output, outputStart);
        lastPass(output, outputStart);
    }

    // The first and last 8 values, the last ones in reverse, which are butterflied together.
    private void loadFirstHalf(final float[] input, final int start) {
        System.arraycopy(input, start, m[4], 0, LANES);
        for (var lane = 0; lane < HALF; lane++) {
            m[5][lane] = input[start + SIZE - 1 - lane];
            m[5][HALF + lane] = input[start + 27 - lane];
        }
    }

    // The middle 16 values, the first 8 in reverse.
    private void loadSecondHalf(final float[] input, final int start) {
        System.arraycopy(input, start + 16, m[2], 0, LANES);
        for (var lane = 0; lane < HALF; lane++) {
            m[6][lane] = input[start + 15 - lane];
            m[6][HALF + lane] = input[start + 11 - lane];
        }
    }
    // BUTTERFLY: the difference of the registers scaled, and their sum.
    private void butterfly(final int first, final int second, final float[] cosines, final int temporary) {
        for (var lane = 0; lane < LANES; lane++) {
            m[temporary][lane] = m[first][lane] - m[second][lane];
            m[second][lane] = m[second][lane] + m[first][lane];
            m[first][lane] = m[temporary][lane] * cosines[lane];
        }
    }

    private void thirdPass() {
        permute(3, 6, 4, 0x31);
        permute(1, 6, 4, 0x20);
        reverseLanes(3);
        butterfly(1, 3, COSINES_3, 6);
        permute(4, 5, 2, 0x20);
        permute(5, 5, 2, 0x31);
        reverseLanes(5);
        butterfly(4, 5, COSINES_3, 6);
    }

    // vperm2f128: each half of the destination is a half of one source, picked by four bits of the immediate.
    private void permute(final int destination, final int first, final int second, final int immediate) {
        copyHalf(first, second, immediate & 0xF, 0);
        copyHalf(first, second, immediate >> 4 & 0xF, HALF);
        System.arraycopy(permuted, 0, m[destination], 0, LANES);
    }

    private void copyHalf(final int first, final int second, final int selector, final int destinationStart) {
        var source = m[first];
        if ((selector & 2) != 0) {
            source = m[second];
        }
        System.arraycopy(source, (selector & 1) * HALF, permuted, destinationStart, HALF);
    }

    private void reverseLanes(final int register) {
        for (var half = 0; half < LANES; half += HALF) {
            for (var lane = 0; lane < HALF / 2; lane++) {
                final var held = m[register][half + lane];
                m[register][half + lane] = m[register][half + HALF - 1 - lane];
                m[register][half + HALF - 1 - lane] = held;
            }
        }
    }

    // BUTTERFLY2 and BUTTERFLY3: the register's lanes shuffled, added to it with the sign of some flipped, and scaled.
    private void reverseButterfly(final int register, final float[] cosines, final int shuffle) {
        System.arraycopy(m[register], 0, original, 0, LANES);
        for (var half = 0; half < LANES; half += HALF) {
            for (var lane = 0; lane < HALF; lane++) {
                final var shuffled = original[half + (shuffle >> (2 * lane) & 3)];
                final var flipped = flipped(shuffle, half + lane, original[half + lane]);
                m[register][half + lane] = (shuffled + flipped) * cosines[half + lane];
            }
        }
    }

    // The sign mask is {0, 0, 1, 1} in each half for the first pass, and {0, 1, 0, 1} for the second.
    private static float flipped(final int shuffle, final int lane, final float value) {
        final var negated = shuffle == 0x1b && (NEGATE_PAIRS >> lane & 1) == 1 || shuffle == 0xb1 && (lane & 1) == 1;
        if (negated) {
            return -value;
        }
        return value;
    }

    private void storePasses(final float[] output, final int start) {
        System.arraycopy(m[3], 0, output, start, LANES);
        System.arraycopy(m[5], 0, output, start + 8, HALF);
        System.arraycopy(m[4], 0, output, start + 12, HALF);
        System.arraycopy(m[5], HALF, output, start + 16, HALF);
        System.arraycopy(m[4], HALF, output, start + 20, HALF);
        System.arraycopy(m[1], 0, output, start + 24, LANES);
    }

    // PASS6_AND_PERMUTE, which adds one float at a time. It starts with what the last pass left in the first lanes of
    // m1, m4, m5, and of the upper halves of m1 and m3.
    private void lastPass(final float[] o, final int s) {
        var m0 = m[1][HALF];
        var m1 = m[1][0];
        var m4 = m[4][0];
        var m5 = m[5][0];
        var m6 = m[3][HALF];
        float m2;
        float m3;
        float m7;
        final var saved1 = o[s + 1];
        m7 = o[s + 18];
        m7 += o[s + 19];
        m3 = o[s + 14];
        m3 += o[s + 15];
        m4 += m3;
        m2 = o[s + 13];
        m2 += m3;
        m3 = o[s + 26];
        m3 += o[s + 27];
        m1 += m3;
        m5 += m4;
        o[s + 4] = m1;
        m1 = o[s + 25];
        m1 += m3;
        m3 = o[s + 10];
        o[s + 12] = m1;
        m3 += o[s + 11];
        m1 = o[s + 25];
        m4 += m3;
        m3 += m2;
        m1 += o[s + 27];
        o[s + 10] = m3;
        m2 += o[s + 9];
        m3 = o[s + 2];
        o[s + 14] = m2;
        m3 += o[s + 3];
        o[s + 8] = m3;
        m3 = o[s + 20];
        o[s + 2] = m5;
        o[s + 20] = m1;
        m2 = o[s + 13];
        m5 = o[s + 30];
        m5 += o[s + 31];
        m1 = o[s + 16];
        m2 += o[s + 15];
        m0 += m5;
        m5 += o[s + 29];
        o[s + 16] = saved1;
        m6 += m0;
        m1 += m6;
        o[s + 24] = o[s + 3];
        o[s + 1] = m1;
        m1 = o[s + 6];
        o[s + 6] = m4;
        m4 = o[s + 22];
        m4 += o[s + 23];
        m3 += m4;
        m4 += o[s + 21];
        final var saved27 = o[s + 27];
        m1 += o[s + 7];
        m0 += m1;
        m1 += m5;
        m6 += m3;
        m3 += m0;
        m0 += m7;
        m5 += o[s + 5];
        m7 += m1;
        o[s + 3] = m6;
        o[s + 28] = saved27;
        m6 = o[s + 7];
        o[s + 7] = m0;
        m0 = o[s + 9];
        o[s + 9] = m7;
        m1 += m4;
        m7 = o[s + 29];
        m0 += m2;
        m7 += o[s + 31];
        o[s + 18] = m0;
        m0 = o[s + 11];
        m2 += m0;
        o[s + 11] = m1;
        o[s + 22] = m2;
        m0 += o[s + 15];
        o[s + 30] = o[s + 15];
        o[s + 26] = m0;
        m4 += m5;
        m5 += o[s + 17];
        o[s + 13] = m4;
        o[s + 15] = m5;
        m4 = o[s + 17];
        m5 = o[s + 5];
        o[s + 5] = m3;
        m5 += m7;
        m7 += m6;
        m4 += m5;
        m2 = o[s + 21];
        m2 += o[s + 23];
        m5 += m2;
        o[s + 17] = m4;
        m2 += m7;
        m4 = o[s + 19];
        o[s + 21] = m2;
        o[s + 19] = m5;
        m7 += m4;
        m6 += o[s + 31];
        m4 += m6;
        m6 += o[s + 23];
        o[s + 25] = m4;
        o[s + 27] = m6;
        m6 = o[s + 23];
        o[s + 23] = m7;
        m6 += o[s + 31];
        o[s + 29] = m6;
    }
}
