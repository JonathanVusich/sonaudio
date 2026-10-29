package dev.javax.sonaudio.format.mp3;

/**
 * One 36-point IMDCT with its window and overlap, in the order of operations of the AVX version of FFmpeg's
 * {@code imdct36_float} (libavcodec/x86/imdct36.asm), which it uses for the blocks of a granule that don't fill a
 * group of four, so that it gives the same floats. Each instruction of the assembly is a call on four-lane registers.
 * The decoder keeps one, which only holds the registers.
 */
final class SingleImdct36 {

    private static final int ROW = Spectrum.SUBBANDS;
    private static final int STRIDE = 4;
    private static final int KEEP_UPPER_THREE = 0b1110;
    private static final int KEEP_ODD = 0b1010;
    private static final int KEEP_LAST = 0b1000;
    private static final int KEEP_SECOND = 0b0010;
    private static final int NEGATE_UPPER_HALF = 0b1100;

    private static final float[] VALUES_1 = {-0.5f, -0.5f, -0.8660254038f, -0.8660254038f};
    private static final float[] VALUES_2 = {1.0f, 1.0f, 0.8660254038f, 0.8660254038f};
    private static final float[] VALUES_3 = {0.1736481777f, 0.1736481777f, 0.3420201433f, 0.3420201433f};
    private static final float[] VALUES_4 = {-0.7660444431f, -0.7660444431f, 0.8660254038f, 0.8660254038f};
    private static final float[] VALUES_5 = {-0.9396926208f, -0.9396926208f, -0.9848077530f, -0.9848077530f};
    private static final float[] VALUES_6 = {0.5f, 0.5f, -0.6427876097f, -0.6427876097f};
    private static final float[] VALUES_7 = {1.0f, 1.0f, -0.6427876097f, -0.6427876097f};
    private static final float[][] COSH = {
        {1.0f, -0.50190991877167369479f, 1.0f, -5.73685662283492756461f},
        {1.0f, -0.51763809020504152469f, 1.0f, -1.93185165257813657349f},
        {1.0f, -0.55168895948124587824f, -1.0f, 1.18310079157624925896f},
        {1.0f, -0.61038729438072803416f, -1.0f, 0.87172339781054900991f},
        {1.0f, -0.70710678118654752439f, 0.0f, 0.0f}
    };

    private final float[][] registers = new float[9][Vectors.LANES];
    private final float[] weights = new float[Vectors.LANES];
    private final float[] gathered = new float[Vectors.LANES];

    /**
     * Transforms a subband's 18 lines, windows them, and adds the previous block's overlap.
     *
     * @param output the granule's subband samples, 18 instants of 32 subbands, where the block's go in its column
     * @param buffer the overlap of the blocks before, where this block's entries are 4 apart from {@code bufferStart};
     *     the block's new overlap replaces them
     * @param window the 40 coefficients of the block's window
     */
    void transform(
            final float[] output,
            final int column,
            final float[] buffer,
            final int bufferStart,
            final float[] input,
            final int inputStart,
            final float[] window) {
        sumLines(input, inputStart);
        multiplyTerms();
        butterfly(0, 1, 0);
        butterfly(7, 2, 1);
        butterfly(3, 6, 2);
        butterfly(4, 1, 3);
        lastButterfly(5, 1, 4);
        permute();
        windowAndOverlap(output, column, buffer, bufferStart, window);
    }

    // for (i = 17; i >= 1; i--) in[i] += in[i-1]; then for (i = 17; i >= 3; i -= 2) in[i] += in[i-2]
    private void sumLines(final float[] input, final int start) {
        final var m = registers;
        load(m[0], input, start);
        load(m[1], input, start + 4);
        Vectors.rotateLeft(m[5], m[0], m[1]);
        Vectors.shuffle(m[6], m[0], m[0], 0x93);
        Vectors.keep(m[6], m[6], KEEP_UPPER_THREE);
        Vectors.add(m[0], m[0], m[6]);
        load(m[2], input, start + 8);
        Vectors.rotateLeft(m[7], m[1], m[2]);
        Vectors.add(m[1], m[1], m[5]);
        load(m[3], input, start + 12);
        Vectors.rotateLeft(m[5], m[2], m[3]);
        Vectors.set(m[4], input[start + 16], input[start + 17], 0, 0);
        Vectors.shuffle(m[6], m[3], m[4], 0x4e);
        Vectors.shuffle(m[6], m[6], m[4], 0xa9);
        Vectors.add(m[4], m[4], m[6]);
        Vectors.add(m[2], m[2], m[7]);
        Vectors.add(m[3], m[3], m[5]);
        Vectors.lowHigh(m[5], m[5], m[0]);
        Vectors.keep(m[5], m[5], KEEP_LAST);
        Vectors.shuffle(m[7], m[0], m[1], 0x4e);
        Vectors.keep(m[7], m[7], KEEP_ODD);
        Vectors.add(m[0], m[0], m[5]);
        Vectors.shuffle(m[6], m[1], m[2], 0x4e);
        Vectors.keep(m[6], m[6], KEEP_ODD);
        Vectors.add(m[1], m[1], m[7]);
        Vectors.shuffle(m[7], m[2], m[3], 0x4e);
        Vectors.keep(m[7], m[7], KEEP_ODD);
        Vectors.add(m[2], m[2], m[6]);
        Vectors.highLow(m[6], m[6], m[3]);
        Vectors.keep(m[6], m[6], KEEP_SECOND);
        Vectors.add(m[3], m[3], m[7]);
        Vectors.add(m[4], m[4], m[6]);
    }

    // Populates tmp[], which ends up as {SwapLH(m0), SwapLH(m7), m3, m4, m5}.
    private void multiplyTerms() {
        final var m = registers;
        Vectors.lowHigh(m[6], m[1], m[5]);
        Vectors.sub(m[6], m[6], m[4]);
        Vectors.sub(m[5], m[0], m[3]);
        swap(5, 8);
        Vectors.mul(m[7], m[2], VALUES_1);
        Vectors.mul(m[5], m[8], VALUES_2);
        Vectors.add(m[7], m[7], m[5]);
        Vectors.mul(m[5], m[6], VALUES_1);
        Vectors.sub(m[7], m[7], m[5]);
        swap(5, 8);
        Vectors.sub(m[5], m[5], m[6]);
        Vectors.add(m[5], m[5], m[2]);
        Vectors.shuffle(m[6], m[4], m[3], 0xe4);
        Vectors.sub(m[6], m[6], m[2]);
        Vectors.mul(m[6], m[6], VALUES_3);
        Vectors.add(m[4], m[4], m[1]);
        Vectors.mul(m[4], m[4], VALUES_4);
        Vectors.shuffle(m[1], m[1], m[0], 0xe4);
        Vectors.add(m[1], m[1], m[2]);
        Vectors.mul(m[1], m[1], VALUES_5);
        Vectors.mul(m[3], m[3], VALUES_6);
        Vectors.mul(m[0], m[0], VALUES_7);
        Vectors.add(m[0], m[0], m[3]);
        Vectors.negate(m[2], m[1], NEGATE_UPPER_HALF);
        Vectors.sub(m[2], m[2], m[4]);
        Vectors.add(m[2], m[2], m[0]);
        Vectors.add(m[3], m[4], m[0]);
        Vectors.sub(m[3], m[3], m[6]);
        Vectors.negate(m[3], m[3], NEGATE_UPPER_HALF);
        Vectors.shuffle(m[0], m[0], m[4], 0xe4);
        Vectors.sub(m[0], m[0], m[1]);
        Vectors.add(m[0], m[0], m[6]);
        Vectors.shuffle(m[4], m[2], m[3], 0x4e);
        Vectors.shuffle(m[3], m[3], m[2], 0x4e);
    }

    // BUTTERF: the register's halves swapped and added to it with the upper pair negated, scaled, and the pairs of
    // lanes combined by addsub.
    private void butterfly(final int value, final int temporary, final int row) {
        final var m = registers;
        Vectors.shuffle(m[temporary], m[value], m[value], 0x4e);
        Vectors.negate(m[value], m[value], NEGATE_UPPER_HALF);
        Vectors.add(m[value], m[value], m[temporary]);
        Vectors.mul(m[value], m[value], COSH[row]);
        Vectors.shuffle(m[temporary], m[value], m[value], 0xb1);
        Vectors.addSub(m[value], m[value], m[temporary]);
    }

    // BUTTERF2: the same without the halves swapped.
    private void lastButterfly(final int value, final int temporary, final int row) {
        final var m = registers;
        Vectors.mul(m[value], m[value], COSH[row]);
        Vectors.shuffle(m[temporary], m[value], m[value], 0xe1);
        Vectors.addSub(m[value], m[value], m[temporary]);
    }

    // The butterflies' outputs, in the order the window takes them.
    private void permute() {
        final var m = registers;
        Vectors.unpackHigh(m[1], m[0], m[7]);
        Vectors.unpackHigh(m[6], m[3], m[4]);
        Vectors.highLow(m[2], m[6], m[1]);
        Vectors.lowHigh(m[1], m[1], m[6]);
        Vectors.unpackLow(m[5], m[5], m[4]);
        Vectors.unpackLow(m[3], m[3], m[7]);
        Vectors.highLow(m[4], m[3], m[5]);
        Vectors.lowHigh(m[5], m[5], m[3]);
        swap(4, 3);
    }

    private void windowAndOverlap(
            final float[] output, final int column, final float[] buffer, final int bufferStart, final float[] window) {
        final var m = registers;
        Vectors.shuffle(m[6], m[2], m[2], 0xb1);
        Vectors.set(m[4], buffer[bufferStart + 68], 0, 0, 0);
        Vectors.set(m[7], buffer[bufferStart + 64], 0, 0, 0);
        Vectors.unpackLow(m[7], m[7], m[4]);
        Vectors.mul(m[6], m[6], weights(window, 16));
        Vectors.add(m[6], m[6], m[7]);
        output[16 * ROW + column] = m[6][0];
        Vectors.shuffle(m[6], m[6], m[6], 0xb1);
        output[17 * ROW + column] = m[6][0];

        Vectors.mul(m[6], m[3], weights(window, 4));
        Vectors.gather(gathered, buffer, bufferStart + 16, STRIDE);
        Vectors.add(m[6], m[6], gathered);
        Vectors.scatter(output, 4 * ROW + column, ROW, m[6]);

        Vectors.shuffle(m[4], m[0], m[3], 0xb5);
        Vectors.mul(m[4], m[4], weights(window, 8));
        Vectors.gather(gathered, buffer, bufferStart + 32, STRIDE);
        Vectors.add(m[4], m[4], gathered);
        Vectors.scatter(output, 8 * ROW + column, ROW, m[4]);

        Vectors.shuffle(m[3], m[3], m[2], 0xb1);
        Vectors.mul(m[3], m[3], weights(window, 12));
        Vectors.gather(gathered, buffer, bufferStart + 48, STRIDE);
        Vectors.add(m[3], m[3], gathered);
        Vectors.scatter(output, 12 * ROW + column, ROW, m[3]);

        Vectors.mul(m[2], m[2], weights(window, 0));
        Vectors.gather(gathered, buffer, bufferStart, STRIDE);
        Vectors.add(m[2], m[2], gathered);
        Vectors.scatter(output, column, ROW, m[2]);

        Vectors.mul(m[4], m[1], weights(window, 20));
        Vectors.scatter(buffer, bufferStart, STRIDE, m[4]);
        Vectors.mul(m[3], m[5], weights(window, 24));
        Vectors.scatter(buffer, bufferStart + 16, STRIDE, m[3]);
        Vectors.shuffle(m[0], m[0], m[5], 0xb0);
        Vectors.mul(m[0], m[0], weights(window, 28));
        Vectors.scatter(buffer, bufferStart + 32, STRIDE, m[0]);
        Vectors.shuffle(m[5], m[5], m[1], 0xb1);
        Vectors.mul(m[5], m[5], weights(window, 32));
        Vectors.scatter(buffer, bufferStart + 48, STRIDE, m[5]);
        Vectors.shuffle(m[1], m[1], m[1], 0xb1);
        Vectors.mul(m[1], m[1], weights(window, 36));
        buffer[bufferStart + 64] = m[1][0];
        Vectors.shuffle(m[1], m[1], m[1], 0xb1);
        buffer[bufferStart + 68] = m[1][0];
    }

    private float[] weights(final float[] window, final int start) {
        System.arraycopy(window, start, weights, 0, Vectors.LANES);
        return weights;
    }

    private void swap(final int first, final int second) {
        final var held = registers[first];
        registers[first] = registers[second];
        registers[second] = held;
    }

    private static void load(final float[] destination, final float[] source, final int start) {
        System.arraycopy(source, start, destination, 0, Vectors.LANES);
    }
}
