package dev.javax.sonaudio.format.opus;

/**
 * The anti-collapse step of a CELT frame (RFC 6716 section 4.3.5): where a short block of a band had no pulses, it
 * is filled with noise of the level its energy and the bits spent on the band call for, so that a transient doesn't
 * leave holes in the spectrum.
 */
final class CeltAntiCollapse {

    private static final double SQUARE_ROOT_OF_TWO = 1.41421356237309504880;
    private static final int NOISE_BIT = 0x8000;

    private final CeltNoise noise;
    private final CeltEnergy energy;

    /**
     * @param noise the frame's random number generator
     * @param energy the energies of the frame and of the frames before it
     */
    CeltAntiCollapse(final CeltNoise noise, final CeltEnergy energy) {
        this.noise = noise;
        this.energy = energy;
    }

    /**
     * Fills the collapsed blocks of one channel's bands.
     *
     * @param channel the channel
     * @param frame the frame
     * @param allocator the frame's allocation, for the pulses of each band
     * @param bands the decoded bands, for which blocks of each collapsed
     * @param coefficients the channel's normalised coefficients
     */
    void fillCollapsedBlocks(
            final int channel,
            final CeltFrame frame,
            final CeltBitAllocator allocator,
            final CeltBandDecoder bands,
            final float[] coefficients) {
        for (var band = frame.startBand(); band < frame.endBand(); band++) {
            final var size = frame.bandSize(band);
            final var level = noiseLevel(channel, frame, allocator, band);
            var renormalize = false;
            for (var block = 0; block < 1 << frame.sizeShift(); block++) {
                if ((bands.collapseMask(channel, band) & 1 << block) == 0) {
                    fillBlock(coefficients, frame.bandStart(band), size, block, frame.sizeShift(), level);
                    renormalize = true;
                }
            }
            if (renormalize) {
                CeltVectorUtils.renormalize(coefficients, frame.bandStart(band), size, 1.0f);
            }
        }
    }

    private float noiseLevel(
            final int channel, final CeltFrame frame, final CeltBitAllocator allocator, final int band) {
        final var size = frame.bandSize(band);
        final var depth = (1 + allocator.pulses(band)) / size;
        final var threshold = (float) Math.pow(2.0, (float) (-1.0 - 0.125f * depth));
        final var root = 1.0f / (float) Math.sqrt(size);
        var previous = energy.previousEnergy(channel, 0, band);
        var older = energy.previousEnergy(channel, 1, band);
        if (frame.channels() == 1) {
            previous = Math.max(previous, energy.previousEnergy(1, 0, band));
            older = Math.max(older, energy.previousEnergy(1, 1, band));
        }
        final var difference = Math.max(0, energy.energy(channel, band) - Math.min(previous, older));
        var level = (float) Math.pow(2.0, 1 - difference);
        if (frame.sizeShift() == 3) {
            level = (float) (level * SQUARE_ROOT_OF_TWO);
        }
        return Math.min(threshold, level) * root;
    }

    private void fillBlock(
            final float[] coefficients,
            final int start,
            final int size,
            final int block,
            final int sizeShift,
            final float level) {
        for (var index = 0; index < size >> sizeShift; index++) {
            var value = -level;
            if ((noise.next() & NOISE_BIT) != 0) {
                value = level;
            }
            coefficients[start + (index << sizeShift) + block] = value;
        }
    }
}
