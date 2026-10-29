package dev.javax.sonaudio.format.mp3;

import dev.javax.sonaudio.exception.InvalidFormatException;

/**
 * Which window a granule's channel is transformed with: one long block, the long blocks that start and stop a run of
 * short blocks, or three short blocks (ISO/IEC 11172-3 section 2.4.2.7).
 */
enum BlockType {
    NORMAL,
    START,
    SHORT,
    STOP;

    /**
     * @param blockTypeBits a window-switching granule's block_type field, which may not be 0, as a granule that
     *     switches windows doesn't use a normal block
     */
    static BlockType fromSwitchedBits(final int blockTypeBits) throws InvalidFormatException {
        return switch (blockTypeBits) {
            case 1 -> START;
            case 2 -> SHORT;
            case 3 -> STOP;
            default -> throw new InvalidFormatException("MP3 granule switches windows to a normal block!");
        };
    }
}
