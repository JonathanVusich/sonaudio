package dev.javax.sonaudio.format.alac;

/**
 * A {@code data} box of an {@code ilst} item, which holds one value of it.
 *
 * @param type how the value is coded: 1 for UTF-8 text, 0 for what the item's own type says, 21 for a number
 * @param value the value's bytes, which the box keeps
 */
record DataAtom(int type, byte[] value) {}
