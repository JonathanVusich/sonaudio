package dev.javax.sonaudio.format.mp3;

/**
 * Four 36-point IMDCTs at once, one in each lane, with their windows and overlaps, in the order of operations of the
 * AVX version of FFmpeg's {@code four_imdct36_float} (libavcodec/x86/imdct36.asm), which it uses for each group of
 * four subbands, so that it gives the same floats. Each instruction of the assembly is a call on four-lane registers,
 * and a register that the assembly saves by swapping names is swapped here. The decoder keeps one, which only holds
 * the registers.
 */
final class FourImdct36 {

    private static final int ROW = Spectrum.SUBBANDS;
    private static final int BLOCK = 18;
    private static final int GROUP = 4;

    private static final float[][] COSTABS = {
        constant(0.98480773f),
        constant(0.93969262f),
        constant(0.86602539f),
        constant(-0.76604444f),
        constant(-0.64278764f),
        constant(0.50000000f),
        constant(-0.50000000f),
        constant(-0.34202015f),
        constant(-0.17364818f),
        constant(0.50190992f),
        constant(0.51763808f),
        constant(0.55168896f),
        constant(0.61038726f),
        constant(0.70710677f),
        constant(0.87172341f),
        constant(1.18310082f),
        constant(1.93185163f),
        constant(5.73685646f)
    };

    private final float[][] m = new float[16][Vectors.LANES];
    private final float[][] tmp = new float[8][Vectors.LANES];
    private final float[] weights = new float[Vectors.LANES];
    private final float[] overlap = new float[Vectors.LANES];

    private float[] output;
    private int outputStart;
    private float[] buffer;
    private int bufferStart;
    private float[] window;

    private static float[] constant(final float value) {
        return new float[] {value, value, value, value};
    }

    /**
     * Transforms four subbands' lines, windows them, and adds the previous blocks' overlap.
     *
     * @param output the granule's subband samples, 18 instants of 32 subbands, where the blocks' go from {@code
     *     column}
     * @param buffer the overlap of the blocks before, where each entry has the four blocks' next to each other, 18 of
     *     them from {@code bufferStart}; the blocks' new overlap replaces it
     * @param input the four blocks' 18 lines each, one after another from {@code inputStart}
     * @param window the four blocks' windows, with their 40 coefficients interleaved
     */
    void transform(
            final float[] output,
            final int column,
            final float[] buffer,
            final int bufferStart,
            final float[] input,
            final int inputStart,
            final float[] window) {
        this.output = output;
        this.outputStart = column;
        this.buffer = buffer;
        this.bufferStart = bufferStart;
        this.window = window;
        sumLinesFrom16(input, inputStart);
        sumLinesFrom8(input, inputStart);
        sumLinesFrom4(input, inputStart);
        sumLinesFrom0(input, inputStart);
        butterflies();
        windowFirst();
        windowSecond();
        windowThird();
        windowFourth();
    }

    private void sumLinesFrom16(final float[] input, final int start) {
        Vectors.set(m[0], input[start + 16], input[start + 17], input[start + BLOCK + 16], input[start + BLOCK + 17]);
        Vectors.set(
                m[3],
                input[start + 2 * BLOCK + 16],
                input[start + 2 * BLOCK + 17],
                input[start + 3 * BLOCK + 16],
                input[start + 3 * BLOCK + 17]);
        Vectors.shuffle(m[5], m[0], m[3], 0xdd);
        Vectors.shuffle(m[0], m[0], m[3], 0x88);
        transpose(m[1], m[6], m[7], m[3], input, start + 12);
        Vectors.add(m[4], m[6], m[7]);
        Vectors.copy(tmp[7], m[4]);
        Vectors.add(m[7], m[7], m[3]);
        Vectors.add(m[6], m[6], m[1]);
        Vectors.add(m[3], m[3], m[0]);
        Vectors.add(m[0], m[0], m[5]);
        Vectors.add(m[0], m[0], m[7]);
        Vectors.add(m[7], m[7], m[6]);
        Vectors.copy(tmp[3], m[7]);
        swap(3, 12);
    }

    private void sumLinesFrom8(final float[] input, final int start) {
        transpose(m[4], m[5], m[2], m[7], input, start + 8);
        Vectors.add(m[1], m[1], m[7]);
        swap(1, 11);
        Vectors.add(m[3], m[5], m[2]);
        swap(3, 13);
        Vectors.add(m[7], m[7], m[2]);
        Vectors.add(m[5], m[5], m[4]);
        Vectors.add(m[6], m[6], m[7]);
        Vectors.copy(tmp[0], m[6]);
        Vectors.add(m[7], m[7], m[5]);
        Vectors.copy(tmp[4], m[7]);
    }

    private void sumLinesFrom4(final float[] input, final int start) {
        transpose(m[2], m[7], m[1], m[6], input, start + 4);
        Vectors.add(m[4], m[4], m[6]);
        Vectors.add(m[6], m[6], m[1]);
        Vectors.add(m[1], m[1], m[7]);
        Vectors.add(m[7], m[7], m[2]);
        Vectors.add(m[5], m[5], m[6]);
        swap(5, 15);
        Vectors.add(m[6], m[6], m[7]);
        Vectors.mul(m[6], m[6], COSTABS[2]);
        Vectors.copy(tmp[2], m[6]);
        swap(1, 10);
        swap(0, 14);
    }

    private void sumLinesFrom0(final float[] input, final int start) {
        transpose(m[1], m[6], m[3], m[5], input, start);
        Vectors.add(m[2], m[2], m[5]);
        Vectors.add(m[5], m[5], m[3]);
        Vectors.add(m[7], m[7], m[5]);
        Vectors.add(m[3], m[3], m[6]);
        Vectors.add(m[6], m[6], m[1]);
        swap(7, 8);
        Vectors.add(m[5], m[5], m[6]);
        swap(6, 9);
        Vectors.add(m[6], m[4], m[12]);
        Vectors.sub(m[6], m[6], m[2]);
        swap(7, 11);
        swap(5, 11);
        Vectors.sub(m[5], m[1], m[7]);
        Vectors.mul(m[7], m[7], COSTABS[5]);
        Vectors.add(m[7], m[7], m[1]);
        Vectors.mul(m[0], m[6], COSTABS[6]);
        Vectors.add(m[0], m[0], m[5]);
        Vectors.copy(tmp[6], m[0]);
        Vectors.add(m[6], m[6], m[5]);
        Vectors.copy(tmp[1], m[6]);
        Vectors.add(m[6], m[4], m[2]);
        Vectors.mul(m[6], m[6], COSTABS[1]);
        Vectors.sub(m[4], m[4], m[12]);
        Vectors.mul(m[4], m[4], COSTABS[8]);
        Vectors.add(m[2], m[2], m[12]);
        Vectors.mul(m[2], m[2], COSTABS[3]);
        Vectors.sub(m[5], m[7], m[6]);
        Vectors.sub(m[5], m[5], m[2]);
        Vectors.add(m[6], m[6], m[7]);
        Vectors.add(m[6], m[6], m[4]);
        Vectors.add(m[7], m[7], m[2]);
        Vectors.sub(m[7], m[7], m[4]);
        Vectors.copy(tmp[5], m[7]);
        Vectors.copy(m[2], tmp[7]);
        Vectors.copy(tmp[7], m[5]);
        swap(7, 13);
        Vectors.sub(m[5], m[7], m[2]);
        Vectors.mul(m[5], m[5], COSTABS[7]);
        swap(1, 10);
        Vectors.mul(m[1], m[1], COSTABS[2]);
        Vectors.add(m[4], m[3], m[2]);
        Vectors.mul(m[4], m[4], COSTABS[4]);
        Vectors.add(m[2], m[2], m[7]);
        Vectors.add(m[7], m[7], m[3]);
        Vectors.mul(m[7], m[7], COSTABS[0]);
        Vectors.sub(m[3], m[3], m[2]);
        Vectors.mul(m[3], m[3], COSTABS[2]);
        Vectors.add(m[2], m[7], m[5]);
        Vectors.add(m[2], m[2], m[1]);
        swap(2, 10);
        Vectors.add(m[7], m[7], m[4]);
        Vectors.sub(m[7], m[7], m[1]);
        swap(7, 12);
        Vectors.sub(m[5], m[5], m[4]);
        Vectors.sub(m[5], m[5], m[1]);
        swap(0, 14);
        swap(5, 13);
    }

    private void butterflies() {
        Vectors.add(m[1], m[0], m[15]);
        Vectors.sub(m[1], m[1], m[8]);
        Vectors.copy(m[4], COSTABS[5]);
        Vectors.mul(m[4], m[4], tmp[0]);
        swap(2, 9);
        Vectors.add(m[4], m[4], m[2]);
        Vectors.sub(m[2], m[2], tmp[0]);
        Vectors.mul(m[5], m[1], COSTABS[6]);
        Vectors.add(m[5], m[5], m[2]);
        swap(5, 9);
        Vectors.add(m[2], m[2], m[1]);
        swap(2, 14);
        swap(5, 15);
        Vectors.sub(m[7], m[5], m[0]);
        Vectors.add(m[5], m[5], m[8]);
        Vectors.mul(m[5], m[5], COSTABS[1]);
        Vectors.mul(m[7], m[7], COSTABS[8]);
        Vectors.add(m[0], m[0], m[8]);
        Vectors.mul(m[0], m[0], COSTABS[3]);
        Vectors.sub(m[2], m[4], m[5]);
        Vectors.sub(m[2], m[2], m[0]);
        swap(2, 15);
        Vectors.add(m[5], m[5], m[4]);
        Vectors.add(m[5], m[5], m[7]);
        Vectors.add(m[4], m[4], m[0]);
        Vectors.sub(m[4], m[4], m[7]);
        swap(4, 8);
        Vectors.copy(m[7], tmp[4]);
        Vectors.copy(m[2], tmp[3]);
        Vectors.add(m[0], m[7], m[2]);
        Vectors.sub(m[0], m[0], m[11]);
        Vectors.mul(m[0], m[0], COSTABS[2]);
        Vectors.add(m[4], m[7], m[11]);
        Vectors.mul(m[4], m[4], COSTABS[0]);
        Vectors.sub(m[7], m[7], m[2]);
        Vectors.mul(m[7], m[7], COSTABS[7]);
        Vectors.add(m[2], m[2], m[11]);
        Vectors.mul(m[2], m[2], COSTABS[4]);
        Vectors.add(m[1], m[7], tmp[2]);
        Vectors.add(m[1], m[1], m[4]);
        Vectors.add(m[4], m[4], m[2]);
        Vectors.sub(m[4], m[4], tmp[2]);
        swap(4, 11);
        Vectors.sub(m[7], m[7], m[2]);
        Vectors.sub(m[7], m[7], tmp[2]);
        Vectors.add(m[4], m[6], m[10]);
        Vectors.sub(m[6], m[6], m[10]);
        Vectors.add(m[2], m[5], m[1]);
        Vectors.mul(m[2], m[2], COSTABS[9]);
        Vectors.sub(m[5], m[5], m[1]);
        Vectors.mul(m[5], m[5], COSTABS[17]);
        Vectors.sub(m[1], m[4], m[2]);
        Vectors.add(m[4], m[4], m[2]);
    }

    private void windowFirst() {
        windowOutput(2, 1, 36, 9);
        windowOutput(1, 1, 32, 8);
        windowBuffer(1, 4, 116, 36);
        windowBuffer(4, 4, 112, 32);
        Vectors.add(m[2], m[6], m[5]);
        Vectors.sub(m[6], m[6], m[5]);
        windowOutput(1, 6, 68, 17);
        windowOutput(6, 6, 0, 0);
        windowBuffer(1, 2, 148, 68);
        windowBuffer(2, 2, 80, 0);
    }

    private void windowSecond() {
        Vectors.add(m[5], m[3], tmp[6]);
        Vectors.copy(m[2], tmp[6]);
        Vectors.sub(m[2], m[2], m[3]);
        Vectors.copy(m[1], m[9]);
        Vectors.sub(m[1], m[1], m[0]);
        Vectors.mul(m[1], m[1], COSTABS[10]);
        Vectors.add(m[0], m[0], m[9]);
        Vectors.mul(m[0], m[0], COSTABS[16]);
        Vectors.add(m[6], m[5], m[1]);
        Vectors.sub(m[5], m[5], m[1]);
        windowOutput(3, 5, 40, 10);
        windowOutput(5, 5, 28, 7);
        windowBuffer(1, 6, 120, 40);
        windowBuffer(6, 6, 108, 28);
        Vectors.add(m[1], m[2], m[0]);
        Vectors.sub(m[2], m[2], m[0]);
        windowOutput(5, 2, 64, 16);
        windowOutput(2, 2, 4, 1);
        windowBuffer(0, 1, 144, 64);
        windowBuffer(1, 1, 84, 4);
    }

    private void windowThird() {
        Vectors.copy(m[1], tmp[7]);
        Vectors.copy(m[5], m[1]);
        Vectors.add(m[1], m[1], m[13]);
        Vectors.sub(m[5], m[5], m[13]);
        swap(3, 15);
        Vectors.add(m[2], m[7], m[3]);
        Vectors.mul(m[2], m[2], COSTABS[11]);
        Vectors.sub(m[3], m[3], m[7]);
        Vectors.mul(m[3], m[3], COSTABS[15]);
        Vectors.add(m[0], m[2], m[1]);
        Vectors.sub(m[1], m[1], m[2]);
        swap(0, 2);
        windowOutput(6, 1, 44, 11);
        windowOutput(1, 1, 24, 6);
        windowBuffer(0, 2, 124, 44);
        windowBuffer(2, 2, 104, 24);
        Vectors.add(m[0], m[5], m[3]);
        Vectors.sub(m[5], m[5], m[3]);
        windowOutput(1, 5, 60, 15);
        windowOutput(5, 5, 8, 2);
        windowBuffer(1, 0, 140, 60);
        windowBuffer(0, 0, 88, 8);
    }

    private void windowFourth() {
        Vectors.copy(m[1], tmp[5]);
        Vectors.add(m[1], m[1], m[12]);
        Vectors.copy(m[2], tmp[5]);
        Vectors.sub(m[2], m[2], m[12]);
        swap(7, 8);
        Vectors.sub(m[0], m[7], m[11]);
        Vectors.add(m[7], m[7], m[11]);
        Vectors.mul(m[4], m[7], COSTABS[12]);
        Vectors.mul(m[0], m[0], COSTABS[14]);
        Vectors.add(m[5], m[1], m[4]);
        Vectors.sub(m[1], m[1], m[4]);
        windowOutput(7, 1, 48, 12);
        windowOutput(1, 1, 20, 5);
        windowBuffer(1, 5, 128, 48);
        windowBuffer(5, 5, 100, 20);
        Vectors.add(m[6], m[2], m[0]);
        Vectors.sub(m[2], m[2], m[0]);
        windowOutput(1, 2, 56, 14);
        windowOutput(2, 2, 12, 3);
        windowBuffer(0, 6, 136, 56);
        windowBuffer(6, 6, 92, 12);
        swap(0, 14);
        Vectors.mul(m[0], m[0], COSTABS[13]);
        Vectors.copy(m[3], tmp[1]);
        Vectors.add(m[2], m[0], m[3]);
        Vectors.sub(m[3], m[3], m[0]);
        windowOutput(0, 3, 52, 13);
        windowOutput(3, 3, 16, 4);
        windowBuffer(0, 2, 132, 52);
        windowBuffer(2, 2, 96, 16);
    }

    // The register times the window's four coefficients at {@code windowIndex}, added to the overlap that was at
    // {@code bufferIndex}, stored as the four blocks' samples at the instant {@code row}.
    private void windowOutput(final int target, final int source, final int windowIndex, final int row) {
        Vectors.mul(m[target], m[source], weights(windowIndex));
        System.arraycopy(buffer, bufferStart + 4 * row, overlap, 0, Vectors.LANES);
        Vectors.add(m[target], m[target], overlap);
        System.arraycopy(m[target], 0, output, outputStart + row * ROW, Vectors.LANES);
    }

    // The register times the window's coefficients, stored as the four blocks' new overlap at {@code bufferIndex}.
    private void windowBuffer(final int target, final int source, final int windowIndex, final int bufferIndex) {
        Vectors.mul(m[target], m[source], weights(windowIndex));
        System.arraycopy(m[target], 0, buffer, bufferStart + bufferIndex, Vectors.LANES);
    }

    private float[] weights(final int index) {
        System.arraycopy(window, index, weights, 0, Vectors.LANES);
        return weights;
    }

    // TRANSPOSE4x4PS: the four blocks' floats at {@code offset}, 18 apart, as four registers of one float of each.
    private void transpose(
            final float[] first,
            final float[] second,
            final float[] third,
            final float[] fourth,
            final float[] input,
            final int start) {
        for (var lane = 0; lane < GROUP; lane++) {
            first[lane] = input[start + lane * BLOCK];
            second[lane] = input[start + 1 + lane * BLOCK];
            third[lane] = input[start + 2 + lane * BLOCK];
            fourth[lane] = input[start + 3 + lane * BLOCK];
        }
    }

    private void swap(final int first, final int second) {
        final var held = m[first];
        m[first] = m[second];
        m[second] = held;
    }
}
