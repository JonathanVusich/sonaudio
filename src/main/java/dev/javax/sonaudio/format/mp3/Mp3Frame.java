package dev.javax.sonaudio.format.mp3;

/**
 * A Layer III frame as {@link Mp3Decoder} reads it, whose main data is in the {@link BitReservoir} until the next frame
 * is read. Each granule's channel starts where the ones before it end, as their part2_3_lengths give.
 *
 * @param granulesStart where the frame's granules start in the reservoir, in bytes, which is before its first byte when
 *     the frame reaches back past the start of the stream
 */
record Mp3Frame(FrameHeader header, SideInfo sideInfo, int granulesStart) {}
