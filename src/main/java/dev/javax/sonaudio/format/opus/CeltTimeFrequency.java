package dev.javax.sonaudio.format.opus;

/**
 * The time-frequency resolution changes of a CELT frame (RFC 6716 section 4.3.1): for each band, how many levels of
 * Haar transform trade its frequency resolution for time resolution, which is negative, or the other way, which is
 * positive. They are coded as a flag per band that toggles, and one more flag that picks between two readings of them.
 */
final class CeltTimeFrequency {

    private final int[] changes = new int[CeltTables.BAND_COUNT];

    /**
     * @return the change of each band of the last frame decoded, indexed by band, which the next frame rewrites
     */
    int[] changes() {
        return changes;
    }

    /**
     * Decodes the changes of a frame.
     *
     * @param rangeDecoder the frame's range decoder
     * @param frame the frame
     */
    void decode(final RangeDecoder rangeDecoder, final CeltFrame frame) {
        final var transientBlocks = frame.blockMode().ordinal();
        var bits = 4 - 2 * transientBlocks;
        var consumed = rangeDecoder.tell();
        var selectBits = 0;
        if (frame.sizeShift() != 0 && consumed + bits + 1 <= frame.frameBits()) {
            selectBits = 1;
        }
        var toggle = 0;
        var anyChange = 0;
        for (var band = frame.startBand(); band < frame.endBand(); band++) {
            if (consumed + bits + selectBits <= frame.frameBits()) {
                toggle ^= flag(rangeDecoder, bits);
                consumed = rangeDecoder.tell();
                anyChange |= toggle;
            }
            changes[band] = toggle;
            bits = 5 - transientBlocks;
        }
        applySelection(rangeDecoder, frame, selectBits, anyChange);
    }

    private int flag(final RangeDecoder rangeDecoder, final int bits) {
        if (rangeDecoder.decodeBit(bits)) {
            return 1;
        }
        return 0;
    }

    private void applySelection(
            final RangeDecoder rangeDecoder, final CeltFrame frame, final int selectBits, final int anyChange) {
        final var table = CeltTables.TF_SELECT[frame.sizeShift()][frame.blockMode().ordinal()];
        var select = 0;
        if (selectBits != 0 && table[0][anyChange] != table[1][anyChange]) {
            select = flag(rangeDecoder, 1);
        }
        for (var band = frame.startBand(); band < frame.endBand(); band++) {
            changes[band] = table[select][changes[band]];
        }
    }
}
