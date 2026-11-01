package dev.javax.sonaudio.format.mp3;

/**
 * The polyphase synthesis filterbank, which turns each instant's 32 subband samples into 32 PCM samples, as ISO/IEC
 * 11172-3 Figure A.2 lays it out: the subband samples are matrixed into 64 values pushed onto a history of 1024, and
 * 512 of those are weighted by the synthesis window and summed in 16s. It works in doubles. The decoder keeps one per
 * channel, as each instant uses the history of the instants before.
 */
final class PolyphaseSynthesis {

    private static final int SUBBANDS = Spectrum.SUBBANDS;
    private static final int VALUES = 2 * SUBBANDS;
    private static final int HISTORY = 1024;
    private static final int STEPS = 16;
    // The subband samples come in with full scale at 2^23.
    private static final double INPUT_SCALE = Math.scalb(1.0, -23);
    // cos((16 + i) * (2k + 1) * pi / 64), the matrixing of Figure A.2.
    private static final double[][] MATRIX = matrix();
    private static final double[] WINDOW = window();

    // A Layer II frame's 36 instants are the most synthesized at once.
    private static final int MAX_INSTANTS = 36;

    // The matrixed values, newest first from offset, wrapping round.
    private final double[] history = new double[HISTORY];
    private final double[] input = new double[SUBBANDS];
    private final float[] pcm = new float[MAX_INSTANTS * SUBBANDS];
    private int offset;

    /**
     * Synthesizes 32 samples from each instant's 32 subband samples: a Layer III granule's 18 instants, or a Layer I
     * or II frame's 12 or 36.
     */
    void synthesize(final SubbandSamples subbandSamples, final int instants) {
        for (var instant = 0; instant < instants; instant++) {
            for (var subband = 0; subband < SUBBANDS; subband++) {
                input[subband] = subbandSamples.subbandSample(instant, subband) * INPUT_SCALE;
            }
            offset = (offset - VALUES) & (HISTORY - 1);
            pushMatrixedValues();
            applyWindow(instant * SUBBANDS);
        }
    }

    /**
     * @return the sample at the index of those last synthesized, from -1 to 1 at full scale
     */
    float sample(final int index) {
        return pcm[index];
    }

    private void pushMatrixedValues() {
        for (var value = 0; value < VALUES; value++) {
            var sum = 0.0;
            for (var subband = 0; subband < SUBBANDS; subband++) {
                sum += MATRIX[value][subband] * input[subband];
            }
            history[(offset + value) & (HISTORY - 1)] = sum;
        }
    }

    // Each sample sums 16 values: from every 128 of the history, the sample's among the first 32 and the last 32.
    private void applyWindow(final int outputStart) {
        for (var sample = 0; sample < SUBBANDS; sample++) {
            var sum = 0.0;
            for (var step = 0; step < STEPS; step++) {
                final var start = (step / 2) * 128 + (step % 2) * 96;
                sum += history[(offset + start + sample) & (HISTORY - 1)] * WINDOW[sample + SUBBANDS * step];
            }
            pcm[outputStart + sample] = (float) sum;
        }
    }

    private static double[][] matrix() {
        final var matrix = new double[VALUES][SUBBANDS];
        for (var value = 0; value < VALUES; value++) {
            for (var subband = 0; subband < SUBBANDS; subband++) {
                matrix[value][subband] = Math.cos((16 + value) * (2 * subband + 1) * Math.PI / 64);
            }
        }
        return matrix;
    }

    private static double[] window() {
        final var window = new double[SynthesisWindow.SIZE];
        for (var index = 0; index < window.length; index++) {
            window[index] = SynthesisWindow.coefficient(index);
        }
        return window;
    }
}
