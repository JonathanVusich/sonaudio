package dev.javax.sonaudio.format.opus;

/**
 * What the decoding of one CELT frame's bands depends on, once the frame's header is read.
 *
 * @param sizeShift the base 2 logarithm of the frame's size in short blocks of 120 samples: 0 to 3
 * @param channels how many channels the frame codes, 1 or 2
 * @param startBand the first band it codes
 * @param endBand the band to stop before
 * @param frameBits the frame's size in bits
 * @param blockMode whether it is one long block or short blocks
 */
record CeltFrame(int sizeShift, int channels, int startBand, int endBand, int frameBits, BlockMode blockMode) {

    /**
     * @return how many samples each channel has
     */
    int frameSamples() {
        return CeltWindowTables.LENGTH << sizeShift;
    }

    /**
     * @return how many blocks the frame is transformed in
     */
    int blocks() {
        return switch (blockMode) {
            case LONG -> 1;
            case SHORT -> 1 << sizeShift;
        };
    }

    /**
     * @param band a band
     * @return how many coefficients it has in this frame
     */
    int bandSize(final int band) {
        return CeltTables.BAND_WIDTH[band] << sizeShift;
    }

    /**
     * @param band a band
     * @return the index of its first coefficient
     */
    int bandStart(final int band) {
        return CeltTables.BAND_START[band] << sizeShift;
    }
}
