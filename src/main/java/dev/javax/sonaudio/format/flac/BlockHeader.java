package dev.javax.sonaudio.format.flac;

/**
 * The header every FLAC metadata block starts with (RFC 9639, section 8.1).
 *
 * @param position whether the block is the last before the audio
 * @param blockType the kind of block
 * @param size the block's length in bytes, after its header
 */
public record BlockHeader(BlockPosition position, BlockType blockType, int size) {}
