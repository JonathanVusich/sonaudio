package dev.javax.sonaudio.format.mp3;

/**
 * The polyphase synthesis filterbank, which turns each instant's 32 subband samples into 32 PCM samples (ISO/IEC
 * 11172-3 section 2.4.3.2, Figure A.2): the subband samples are transformed by a 32-point DCT into values pushed onto
 * a history, 512 of which are weighted by the synthesis window and summed in 16s. It works as FFmpeg's floating-point
 * decoder does on x86 processors, as AcoustID's fpcalc decodes with it, in the same order of operations (the DCT as
 * the AVX assembly adds, and the window as the SSE inline assembly does), so the samples come out the same floats. The
 * decoder keeps one per channel, as each instant uses the history of the instants before.
 */
final class PolyphaseSynthesis {

    private static final int SUBBANDS = Spectrum.SUBBANDS;
    private static final int HISTORY = 512;
    private static final int SUMS = 17;
    private static final int PARTS = 8;
    private static final int PART_STRIDE = 64;
    private static final int SHORT_STRIDE = 16;
    // The window FFmpeg's assembly takes with the extra copies it needs, so that it needn't shuffle.
    private static final float[] WINDOW = window();
    private static final int SCALE_BITS = 23;

    // A Layer II frame's 36 instants are the most synthesized at once.
    private static final int MAX_INSTANTS = 36;

    // The history of DCT outputs, with the room to copy the first 32 after the last 512.
    private final float[] history = new float[2 * HISTORY];
    private final float[] input = new float[SUBBANDS];
    private final float[] pcm = new float[MAX_INSTANTS * SUBBANDS];
    private final float[] sumA = new float[SUMS];
    private final float[] sumB = new float[SUMS];
    private final float[] sumC = new float[SUMS];
    private final float[] sumD = new float[SUMS];
    private final Dct32 dct = new Dct32();
    private int offset;

    /**
     * Synthesizes 32 samples from each instant's 32 subband samples: a Layer III granule's 18 instants, or a Layer I
     * or II frame's 12 or 36.
     */
    void synthesize(final SubbandSamples subbandSamples, final int instants) {
        for (var instant = 0; instant < instants; instant++) {
            for (var subband = 0; subband < SUBBANDS; subband++) {
                input[subband] = subbandSamples.subbandSample(instant, subband);
            }
            dct.transform(history, offset, input, 0);
            applyWindow(instant * SUBBANDS);
            offset = offset - SUBBANDS & HISTORY - 1;
        }
    }

    /**
     * @return the sample at the index of those last synthesized, from -1 to 1 at full scale
     */
    float sample(final int index) {
        return pcm[index];
    }

    // FFmpeg's apply_window_mp3 for SSE: four sums of 8 products for 16 samples each, then their combination.
    private void applyWindow(final int outputStart) {
        System.arraycopy(history, offset, history, offset + HISTORY, SUBBANDS);
        sumProducts(offset + SHORT_STRIDE, 0, HISTORY, sumA, sumC);
        sumProducts(offset + 2 * SHORT_STRIDE, 3 * SHORT_STRIDE, HISTORY + 8 * SHORT_STRIDE, sumB, sumD);
        for (var part = 0; part < PARTS; part++) {
            sumA[0] += WINDOW[2 * SHORT_STRIDE + part * PART_STRIDE]
                    * history[offset + 3 * SHORT_STRIDE + part * PART_STRIDE];
        }
        sumC[0] = 0;
        sumB[16] = 0;
        sumD[16] = 0;
        for (var sample = 0; sample < SHORT_STRIDE; sample++) {
            pcm[outputStart + sample] = sumD[SHORT_STRIDE - sample] - sumA[sample];
        }
        for (var sample = 1; sample < SHORT_STRIDE; sample++) {
            pcm[outputStart + SUBBANDS - sample] = sumC[sample] + sumB[SHORT_STRIDE - sample];
        }
        var sum = 0f;
        for (var part = 0; part < PARTS; part++) {
            sum -= WINDOW[3 * SHORT_STRIDE + part * PART_STRIDE]
                    * history[offset + 2 * SHORT_STRIDE + part * PART_STRIDE];
        }
        pcm[outputStart + SHORT_STRIDE] = sum;
    }

    // Two sums for each of 16 samples, of the 8 products of the history with the window, taken away from 0 in turn.
    private void sumProducts(
            final int historyStart,
            final int firstWindow,
            final int secondWindow,
            final float[] first,
            final float[] second) {
        for (var sample = 0; sample < SHORT_STRIDE; sample++) {
            var firstSum = 0f;
            var secondSum = 0f;
            for (var part = 0; part < PARTS; part++) {
                final var value = history[historyStart + sample + part * PART_STRIDE];
                firstSum -= WINDOW[firstWindow + sample + part * PART_STRIDE] * value;
                secondSum -= WINDOW[secondWindow + sample + part * SHORT_STRIDE] * value;
            }
            first[sample] = firstSum;
            second[sample] = secondSum;
        }
    }

    // The 512 coefficients, then the two copies of them FFmpeg's assembly reads: 16 at a time, in reverse order.
    private static float[] window() {
        final var window = new float[512 + 256];
        for (var index = 0; index < 512; index++) {
            window[index] = (float) Math.scalb(SynthesisWindow.coefficient(index), -SCALE_BITS);
        }
        for (var part = 0; part < PARTS; part++) {
            for (var value = 0; value < SHORT_STRIDE; value++) {
                window[512 + SHORT_STRIDE * part + value] = window[PART_STRIDE * part + 32 - value];
                window[512 + 128 + SHORT_STRIDE * part + value] = window[PART_STRIDE * part + 48 - value];
            }
        }
        return window;
    }
}
