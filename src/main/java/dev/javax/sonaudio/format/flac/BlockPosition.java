package dev.javax.sonaudio.format.flac;

// The "last metadata block" flag. Declared in bit order: each constant's ordinal is its bit.
public enum BlockPosition {
    NOT_LAST,
    LAST,
}
