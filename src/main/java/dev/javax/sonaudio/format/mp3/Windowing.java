package dev.javax.sonaudio.format.mp3;

/**
 * How a granule's channel is windowed, and the Huffman tables its big values are coded with, which the
 * window_switching_flag picks between (ISO/IEC 11172-3 section 2.4.1.7), as {@link Mp3Decoder} reads them.
 */
sealed interface Windowing permits Windowing.Normal, Windowing.Switched {

    /**
     * How many windows a short block has.
     */
    int WINDOWS = 3;

    BlockType blockType();

    /**
     * @return which blocks the scale factors cover
     */
    BlockLayout layout();

    LowSubbands lowSubbands();

    /**
     * One long block, whose big values are split into three regions, each with its own table.
     *
     * @param region0Count one less than how many scale factor bands the first region covers
     * @param region1Count one less than how many the second covers, the third taking the rest
     */
    record Normal(int table0, int table1, int table2, int region0Count, int region1Count) implements Windowing {

        @Override
        public BlockType blockType() {
            return BlockType.NORMAL;
        }

        @Override
        public BlockLayout layout() {
            return BlockLayout.LONG;
        }

        @Override
        public LowSubbands lowSubbands() {
            return LowSubbands.BY_BLOCK_TYPE;
        }
    }

    /**
     * A start, stop or short block, whose big values are split into two regions: the first is implicit, and the second
     * takes the rest.
     *
     * @param lowSubbands the mixed_block_flag's choice, whose normal window for every block type also only gives a
     *     short block long scale factor bands below them
     * @param subblockGains each short window's gain, in steps of 8 of the global gain
     */
    record Switched(BlockType blockType, LowSubbands lowSubbands, int table0, int table1, int[] subblockGains)
            implements Windowing {

        @Override
        public int[] subblockGains() {
            return subblockGains.clone();
        }

        int subblockGain(final int window) {
            return subblockGains[window];
        }

        @Override
        public BlockLayout layout() {
            if (blockType != BlockType.SHORT) {
                return BlockLayout.LONG;
            }
            if (lowSubbands == LowSubbands.NORMAL_WINDOW) {
                return BlockLayout.MIXED;
            }
            return BlockLayout.SHORT;
        }
    }
}
