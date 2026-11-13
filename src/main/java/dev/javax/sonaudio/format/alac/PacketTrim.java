package dev.javax.sonaudio.format.alac;

/**
 * Which samples of a decoded packet play, and how many of the file's start samples are still to be skipped after it.
 *
 * @param first the index of the first sample that plays
 * @param count how many samples play, which is 0 when the whole packet is dropped
 * @param pendingSkip how many samples of the packets after this one the start trimming drops
 */
record PacketTrim(int first, int count, long pendingSkip) {}
