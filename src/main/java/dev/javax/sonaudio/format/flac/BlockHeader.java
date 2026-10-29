package dev.javax.sonaudio.format.flac;

public record BlockHeader(BlockPosition position, BlockType blockType, int size) {}
