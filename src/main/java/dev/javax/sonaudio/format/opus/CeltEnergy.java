package dev.javax.sonaudio.format.opus;

import java.util.Arrays;

/**
 * The energy of each band of each channel of a CELT frame (RFC 6716 section 4.3.2), which the frame codes in three
 * parts: a coarse value, predicted from the last frame and the band below, then a few bits of fine energy, then bits
 * that are left over. It keeps the energies of the last two frames, which the next frame's prediction and its
 * anti-collapse look back at.
 */
final class CeltEnergy {

    /**
     * The energy a silent frame leaves in every band, which is far below any a frame codes.
     */
    static final float SILENCE = -28.0f;

    private static final int BAND_COUNT = CeltTables.BAND_COUNT;
    private static final float FLOOR = -9.0f;
    private static final int LAPLACE_ZERO_SHIFT = 7;
    private static final int LAPLACE_DECAY_SHIFT = 6;
    private static final int LAPLACE_MIN_BITS = 15;
    private static final int MAX_FINE_BITS = 8;
    private static final float INTRA_BETA = 1.0f - (4915.0f / 32768.0f);
    private static final float FINE_SCALE = 16384.0f;

    private final float[][] energies = new float[2][BAND_COUNT];
    // The last frame's energies, and those of the frame before it, for each channel.
    private final float[][][] previousEnergies = new float[2][2][BAND_COUNT];
    // What each channel passes from one band to the next while the coarse energy is decoded.
    private final float[] carriedEnergies = new float[2];

    CeltEnergy() {
        reset();
    }

    /**
     * Forgets the energies of past frames.
     */
    void reset() {
        for (var channel = 0; channel < 2; channel++) {
            Arrays.fill(previousEnergies[channel][0], SILENCE);
            Arrays.fill(previousEnergies[channel][1], SILENCE);
            Arrays.fill(energies[channel], 0f);
        }
    }

    /**
     * @param channel 0 or 1
     * @param band a band
     * @return the energy of the band in the frame being decoded, or the last frame's before it is decoded, in log2
     *     units below the band's mean energy
     */
    float energy(final int channel, final int band) {
        return energies[channel][band];
    }

    /**
     * Makes the first channel's energies the larger of the two channels', which is what a mono frame predicts from
     * after a stereo one.
     */
    void mergeChannelsForMono() {
        for (var band = 0; band < BAND_COUNT; band++) {
            energies[0][band] = Math.max(energies[0][band], energies[1][band]);
        }
    }

    /**
     * Decodes the coarse energy of every band of the frame, in steps of 6 dB.
     *
     * @param rangeDecoder the frame's range decoder
     * @param frame the frame
     */
    void decodeCoarse(final RangeDecoder rangeDecoder, final CeltFrame frame) {
        var alpha = CeltTables.ALPHA[frame.sizeShift()];
        var beta = CeltTables.BETA[frame.sizeShift()];
        var model = CeltTables.COARSE_ENERGY_DIST[frame.sizeShift()][0];
        if (rangeDecoder.tell() + 3 <= frame.frameBits() && rangeDecoder.decodeBit(3)) {
            alpha = 0.0f;
            beta = INTRA_BETA;
            model = CeltTables.COARSE_ENERGY_DIST[frame.sizeShift()][1];
        }
        Arrays.fill(carriedEnergies, 0f);
        for (var band = 0; band < BAND_COUNT; band++) {
            for (var channel = 0; channel < frame.channels(); channel++) {
                decodeCoarseBand(rangeDecoder, frame, model, alpha, beta, band, channel);
            }
        }
    }

    private void decodeCoarseBand(
            final RangeDecoder rangeDecoder,
            final CeltFrame frame,
            final int[] model,
            final float alpha,
            final float beta,
            final int band,
            final int channel) {
        if (band < frame.startBand() || band >= frame.endBand()) {
            energies[channel][band] = 0.0f;
            return;
        }
        final var value = decodeCoarseValue(rangeDecoder, frame, model, band);
        energies[channel][band] = Math.max(FLOOR, energies[channel][band]) * alpha + carriedEnergies[channel] + value;
        carriedEnergies[channel] += beta * value;
    }

    // The step of the band's energy from its prediction, by the Laplace distribution when there are bits to spare,
    // and otherwise by cruder models.
    private float decodeCoarseValue(
            final RangeDecoder rangeDecoder, final CeltFrame frame, final int[] model, final int band) {
        final var available = frame.frameBits() - rangeDecoder.tell();
        if (available >= LAPLACE_MIN_BITS) {
            final var index = Math.min(band, 20) << 1;
            return rangeDecoder.decodeLaplace(
                    model[index] << LAPLACE_ZERO_SHIFT, model[index + 1] << LAPLACE_DECAY_SHIFT);
        }
        if (available >= 2) {
            final var symbol = rangeDecoder.decodeIcdf(CeltTables.SMALL_ENERGY_ICDF, CeltTables.SMALL_ENERGY_BITS);
            return (symbol >> 1) ^ -(symbol & 1);
        }
        if (available >= 1 && rangeDecoder.decodeBit(1)) {
            return -1;
        }
        if (available >= 1) {
            return 0;
        }
        return -1;
    }

    /**
     * Decodes the fine energy bits that the allocation gave each band, which refine the coarse energy.
     *
     * @param rangeDecoder the frame's range decoder
     * @param frame the frame
     * @param allocator the allocation of the frame
     */
    void decodeFine(final RangeDecoder rangeDecoder, final CeltFrame frame, final CeltBitAllocator allocator) {
        for (var band = frame.startBand(); band < frame.endBand(); band++) {
            final var bits = allocator.fineBits(band);
            if (bits == 0) {
                continue;
            }
            for (var channel = 0; channel < frame.channels(); channel++) {
                final var raw = rangeDecoder.decodeRawBits(bits);
                energies[channel][band] += (raw + 0.5f) * (1 << (14 - bits)) / FINE_SCALE - 0.5f;
            }
        }
    }

    /**
     * Decodes the bits that are left once the shapes are decoded, one per band and channel, to refine the energy of
     * the bands that were given priority first and then the rest.
     *
     * @param rangeDecoder the frame's range decoder
     * @param frame the frame
     * @param allocator the allocation of the frame
     */
    void decodeFinal(final RangeDecoder rangeDecoder, final CeltFrame frame, final CeltBitAllocator allocator) {
        var bitsLeft = frame.frameBits() - rangeDecoder.tell();
        for (var priority = 0; priority < 2; priority++) {
            for (var band = frame.startBand(); band < frame.endBand() && bitsLeft >= frame.channels(); band++) {
                if (allocator.finePriority(band) != priority || allocator.fineBits(band) >= MAX_FINE_BITS) {
                    continue;
                }
                for (var channel = 0; channel < frame.channels(); channel++) {
                    final var raw = rangeDecoder.decodeRawBits(1);
                    energies[channel][band] += (raw - 0.5f) * (1 << (14 - allocator.fineBits(band) - 1)) / FINE_SCALE;
                    bitsLeft--;
                }
            }
        }
    }

    /**
     * Makes every band's energy silence, as a silent frame does.
     */
    void setSilent() {
        for (var channel = 0; channel < 2; channel++) {
            Arrays.fill(energies[channel], SILENCE);
        }
    }

    /**
     * @param channel 0 or 1
     * @param age 0 for the last frame, 1 for the one before it
     * @param band a band
     * @return the band's energy in that frame
     */
    float previousEnergy(final int channel, final int age, final int band) {
        return previousEnergies[channel][age][band];
    }

    /**
     * Keeps the frame's energies for the next frame to look back at, and clears the bands that it didn't code.
     *
     * @param frame the frame
     * @param codedChannels how many channels it coded
     */
    void finishFrame(final CeltFrame frame, final int codedChannels) {
        if (codedChannels == 1) {
            System.arraycopy(energies[0], 0, energies[1], 0, BAND_COUNT);
        }
        for (var channel = 0; channel < 2; channel++) {
            keepPreviousEnergies(frame, channel);
            for (var band = 0; band < BAND_COUNT; band++) {
                if (band < frame.startBand() || band >= frame.endBand()) {
                    previousEnergies[channel][0][band] = SILENCE;
                    energies[channel][band] = 0.0f;
                }
            }
        }
    }

    // After a long block the last two frames' energies move back one frame. After short blocks the last frame's are
    // lowered to the lesser of its and this frame's, instead.
    private void keepPreviousEnergies(final CeltFrame frame, final int channel) {
        final var last = previousEnergies[channel][0];
        if (frame.blockMode() == CeltBlockMode.LONG) {
            System.arraycopy(last, 0, previousEnergies[channel][1], 0, BAND_COUNT);
            System.arraycopy(energies[channel], 0, last, 0, BAND_COUNT);
            return;
        }
        for (var band = 0; band < BAND_COUNT; band++) {
            last[band] = Math.min(last[band], energies[channel][band]);
        }
    }

    /**
     * Scales the normalised coefficients of a channel back to their energy.
     *
     * @param channel 0 or 1
     * @param frame the frame
     * @param coefficients the channel's coefficients, which are scaled in place
     */
    void denormalize(final int channel, final CeltFrame frame, final float[] coefficients) {
        for (var band = frame.startBand(); band < frame.endBand(); band++) {
            final var logNorm = energies[channel][band] + CeltTables.MEAN_ENERGY[band];
            final var norm = (float) Math.pow(2.0, Math.min(logNorm, 32.0f));
            final var start = frame.bandStart(band);
            for (var index = 0; index < frame.bandSize(band); index++) {
                coefficients[start + index] *= norm;
            }
        }
    }
}
