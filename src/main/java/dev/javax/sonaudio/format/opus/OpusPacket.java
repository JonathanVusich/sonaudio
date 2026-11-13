package dev.javax.sonaudio.format.opus;

/**
 * The framing of one stream's part of an Opus packet (RFC 6716 section 3): what its TOC byte says of every frame in it,
 * and where each frame's bytes are. A multistream packet has one of these for each coded stream.
 */
final class OpusPacket {

    private final OpusMode mode;
    private final OpusBandwidth bandwidth;
    private final ChannelCoding coding;
    private final int frameSamples;
    private final int[] frameOffsets;
    private final int[] frameSizes;
    private final int packetSize;

    /**
     * @param frameSamples how many samples at 48 kHz each frame holds, per channel
     * @param frameOffsets where each frame starts, from the start of this stream's part of the packet, which the
     *     packet keeps: the caller makes it for the packet and doesn't touch it again
     * @param frameSizes how many bytes each frame takes, which the packet keeps in the same way
     * @param packetSize how many bytes this stream's part of the packet takes, so where the next stream's starts
     */
    OpusPacket(
            final OpusMode mode,
            final OpusBandwidth bandwidth,
            final ChannelCoding coding,
            final int frameSamples,
            final int[] frameOffsets,
            final int[] frameSizes,
            final int packetSize) {
        this.mode = mode;
        this.bandwidth = bandwidth;
        this.coding = coding;
        this.frameSamples = frameSamples;
        this.frameOffsets = frameOffsets;
        this.frameSizes = frameSizes;
        this.packetSize = packetSize;
    }

    OpusMode mode() {
        return mode;
    }

    OpusBandwidth bandwidth() {
        return bandwidth;
    }

    ChannelCoding coding() {
        return coding;
    }

    /**
     * @return how many samples at 48 kHz each frame of the packet holds, per channel
     */
    int frameSamples() {
        return frameSamples;
    }

    /**
     * @return how many milliseconds each frame holds, which SILK's superframes are measured in: 10, 20, 40 or 60 (for
     *     CELT's 2.5 ms, 2)
     */
    int frameMillis() {
        return frameSamples / 48;
    }

    int frameCount() {
        return frameSizes.length;
    }

    /**
     * @param frame the frame, from 0
     * @return where it starts, from the start of this stream's part of the packet
     */
    int frameOffset(final int frame) {
        return frameOffsets[frame];
    }

    /**
     * @param frame the frame, from 0
     * @return how many bytes it takes, which may be none
     */
    int frameSize(final int frame) {
        return frameSizes[frame];
    }

    /**
     * @return how many samples the packet holds, per channel, at 48 kHz
     */
    int sampleCount() {
        return frameCount() * frameSamples;
    }

    /**
     * @return how many bytes this stream's part of the packet takes
     */
    int packetSize() {
        return packetSize;
    }
}
