package dev.javax.sonaudio.format.opus;

import dev.javax.sonaudio.exception.InvalidFormatException;

/**
 * An Opus packet as an Ogg stream carries it: its bytes, and how many samples FFmpeg's Ogg demuxer has the decoder
 * drop at the start and the end of the packet's output (its {@code AV_PKT_DATA_SKIP_SAMPLES} side data).
 *
 * @param bytes the packet, which may be empty
 * @param startTrim how many samples the decoder drops from the start of what it decodes, which is the pre-skip for
 *     the first packet and 0 for every other
 * @param endTrim how many samples it drops from the end of what it decodes, which only the packet that ends the
 *     stream can have
 */
record OggPacket(byte[] bytes, int startTrim, int endTrim) {

    private static final int FIRST_HYBRID_CONFIG = 12;
    private static final int FIRST_CELT_CONFIG = 16;
    private static final int ARBITRARY_FRAME_COUNT_MASK = 0x3F;

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    /**
     * Works out how many samples the packet holds from its TOC byte and, for a packet of code 3, its frame count, as
     * FFmpeg's demuxer does to time the packets (RFC 6716 sections 3.1 and 3.2.5).
     *
     * @return the packet's samples per channel at 48 kHz
     * @throws InvalidFormatException when the packet is empty, or has code 3 and nothing after its TOC byte
     */
    int duration() throws InvalidFormatException {
        if (bytes.length == 0) {
            throw new InvalidFormatException("Opus packet is empty");
        }
        final var toc = bytes[0] & 0xFF;
        final var config = toc >> 3;
        final var code = toc & 3;
        return frameSamples(config) * frameCount(code);
    }

    private int frameCount(final int code) throws InvalidFormatException {
        if (code == 3) {
            if (bytes.length < 2) {
                throw new InvalidFormatException("Opus packet of code 3 has no frame count");
            }
            return bytes[1] & ARBITRARY_FRAME_COUNT_MASK;
        }
        if (code == 0) {
            return 1;
        }
        return 2;
    }

    private int frameSamples(final int config) {
        if (config < FIRST_HYBRID_CONFIG) {
            return Math.max(480, 960 * (config & 3));
        }
        if (config < FIRST_CELT_CONFIG) {
            return 480 << (config & 1);
        }
        return 120 << (config & 3);
    }
}
