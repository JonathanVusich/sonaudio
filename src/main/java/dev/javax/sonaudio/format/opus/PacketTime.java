package dev.javax.sonaudio.format.opus;

/**
 * Where in the stream, in 48 kHz samples with the pre-skip included, the next packet starts, when the demuxer knows it
 * from a page's granule position.
 */
sealed interface PacketTime {

    /**
     * @param position the sample the packet starts at
     */
    record Known(long position) implements PacketTime {}

    /**
     * The start of the next packet is not known yet.
     */
    record Unknown() implements PacketTime {}
}
