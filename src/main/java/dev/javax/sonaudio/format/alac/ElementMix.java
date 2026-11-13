package dev.javax.sonaudio.format.alac;

/**
 * What an element of a packet says of how its channels are put together after their samples are read.
 *
 * @param shift how far a stereo pair's mixed channel is shifted down
 * @param weight how much of the pair's second channel is taken from the first, or 0 when the pair isn't mixed
 * @param extraBits how many low bits of each sample are coded apart from the rest, 0, 8, 16 or 24
 */
record ElementMix(int shift, int weight, int extraBits) {}
