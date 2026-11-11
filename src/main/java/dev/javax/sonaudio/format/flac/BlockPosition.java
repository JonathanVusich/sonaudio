package dev.javax.sonaudio.format.flac;

// Declared in bit order: each constant's ordinal is its bit.
/**
 * A metadata block header's "last metadata block" flag.
 */
public enum BlockPosition {
    /**
     * Another metadata block follows.
     */
    NOT_LAST,
    /**
     * The audio follows.
     */
    LAST,
}
