package dev.javax.sonaudio.fingerprint;

import java.util.Arrays;

/**
 * How a signal's energy spreads over the twelve notes of the octave as it plays: a row per overlapping frame of the
 * signal, a column per note, as Chromaprint computes it. Each row is smoothed with its neighbours and normalised.
 */
public final class ChromaImage {

    public static final int BANDS = 12;

    private static final int FRAME_SIZE = 4096;
    private static final int FRAME_STEP = FRAME_SIZE / 3;
    private static final int MIN_FREQUENCY = 28;
    private static final int MAX_FREQUENCY = 3520;
    // A four octaves below 440 Hz, so each octave, and band 0, starts at an A.
    private static final double OCTAVE_BASE = 440.0 / 16;
    private static final double[] SMOOTHING = {0.25, 0.75, 1.0, 0.75, 0.25};
    // Rows quieter than this are left silent rather than normalised, which would amplify noise.
    private static final double SILENCE = 0.01;

    private static final float[] WINDOW = hammingWindow();
    private static final int[] NOTES = noteOfEachBin();

    private final double[][] rows;

    public ChromaImage(final Signal signal) {
        final var chroma = chromaRows(signal);
        final var smoothed = smoothRows(chroma);
        this.rows = normaliseRows(smoothed);
    }

    public int rowCount() {
        return rows.length;
    }

    public double value(final int row, final int band) {
        return rows[row][band];
    }

    private static double[][] chromaRows(final Signal signal) {
        final var fft = new Fft(FRAME_SIZE);
        final var frameCount = Math.max(0, (signal.sampleCount() - FRAME_SIZE) / FRAME_STEP + 1);
        final var rows = new double[frameCount][];
        final var windowed = new float[FRAME_SIZE];
        for (var frame = 0; frame < frameCount; frame++) {
            windowFrame(signal, frame * FRAME_STEP, windowed);
            final var spectrum = fft.powerSpectrum(windowed);
            rows[frame] = noteEnergies(spectrum);
        }
        return rows;
    }

    // The window and the product are single precision, as Chromaprint's are.
    private static void windowFrame(final Signal signal, final int start, final float[] frame) {
        for (var i = 0; i < FRAME_SIZE; i++) {
            frame[i] = signal.sample(start + i) * WINDOW[i];
        }
    }

    private static double[] noteEnergies(final PowerSpectrum spectrum) {
        final var energies = new double[BANDS];
        for (var bin = 0; bin < NOTES.length; bin++) {
            final var note = NOTES[bin];
            if (note >= 0) {
                energies[note] += spectrum.power(bin);
            }
        }
        return energies;
    }

    // Each row is the weighted sum of the rows around it, so it ends shorter by the smoothing's width less one.
    private static double[][] smoothRows(final double[][] rows) {
        final var smoothed = new double[Math.max(0, rows.length - SMOOTHING.length + 1)][BANDS];
        for (var row = 0; row < smoothed.length; row++) {
            for (var offset = 0; offset < SMOOTHING.length; offset++) {
                final var source = rows[row + offset];
                for (var band = 0; band < BANDS; band++) {
                    smoothed[row][band] += SMOOTHING[offset] * source[band];
                }
            }
        }
        return smoothed;
    }

    private static double[][] normaliseRows(final double[][] rows) {
        final var normalised = new double[rows.length][BANDS];
        for (var row = 0; row < rows.length; row++) {
            final var source = rows[row];
            var squares = 0.0;
            for (final var value : source) {
                squares += value * value;
            }
            final var norm = Math.sqrt(squares);
            if (norm < SILENCE) {
                continue;
            }
            for (var band = 0; band < BANDS; band++) {
                normalised[row][band] = source[band] / norm;
            }
        }
        return normalised;
    }

    // A Hamming window, also scaling 16-bit samples to the range -1 to 1, worked out as Chromaprint does.
    private static float[] hammingWindow() {
        final var window = new float[FRAME_SIZE];
        final var scale = 1.0 / Short.MAX_VALUE;
        for (var i = 0; i < FRAME_SIZE; i++) {
            window[i] = (float) (scale * (0.54 - 0.46 * StrictMath.cos(i * 2.0 * Math.PI / (FRAME_SIZE - 1))));
        }
        return window;
    }

    // The note each frequency bin of the spectrum adds to, or -1 for those outside the range Chromaprint reads.
    private static int[] noteOfEachBin() {
        final var notes = new int[FRAME_SIZE / 2 + 1];
        Arrays.fill(notes, -1);
        final var firstBin = Math.max(1, binOfFrequency(MIN_FREQUENCY));
        final var endBin = Math.min(FRAME_SIZE / 2, binOfFrequency(MAX_FREQUENCY));
        for (var bin = firstBin; bin < endBin; bin++) {
            final var frequency = (double) bin * Signal.SAMPLE_RATE / FRAME_SIZE;
            final var octaves = Math.log(frequency / OCTAVE_BASE) / Math.log(2);
            notes[bin] = (int) (BANDS * (octaves - Math.floor(octaves)));
        }
        return notes;
    }

    private static int binOfFrequency(final int frequency) {
        return (int) Math.round((double) FRAME_SIZE * frequency / Signal.SAMPLE_RATE);
    }
}
