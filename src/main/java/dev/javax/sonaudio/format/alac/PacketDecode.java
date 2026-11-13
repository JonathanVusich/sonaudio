package dev.javax.sonaudio.format.alac;

/**
 * What decoding a packet gave: FFmpeg's decoder makes a frame of it only if its elements fill every channel.
 */
sealed interface PacketDecode permits PacketDecode.Complete, PacketDecode.Incomplete {

    /**
     * Every channel was decoded.
     *
     * @param samples how many samples per channel the packet has
     */
    record Complete(int samples) implements PacketDecode {}

    /**
     * The packet's elements left a channel out, so FFmpeg makes no frame of it, and doesn't call it an error.
     */
    record Incomplete() implements PacketDecode {}
}
