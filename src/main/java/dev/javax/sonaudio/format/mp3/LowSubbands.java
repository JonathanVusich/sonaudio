package dev.javax.sonaudio.format.mp3;

/**
 * How a granule's channel transforms its two lowest subbands, which its mixed_block_flag picks (ISO/IEC 11172-3
 * section 2.4.2.7).
 */
enum LowSubbands {
    /**
     * With the window its block type gives them, as every other subband.
     */
    BY_BLOCK_TYPE,
    /**
     * With the normal window, whatever the block type.
     */
    NORMAL_WINDOW
}
