package dev.javax.sonaudio.format.mp3;

/**
 * A table that codes a granule's big values, two at a time (ISO/IEC 11172-3 section 2.4.2.7).
 *
 * @param code codes each pair as one value, {@code x * size + y}
 * @param size how many values x and y can each take
 * @param linbits how many bits follow a value of {@code size - 1}, which are added to it, for values too large for the
 *     table
 */
record BigValueTable(HuffmanCode code, int size, int linbits) {}
