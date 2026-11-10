package dev.javax.sonaudio.format.mp3;

import dev.javax.sonaudio.format.mp3.ParsedHeader.HeaderFound;
import dev.javax.sonaudio.format.mp3.ParsedHeader.NoHeader;
import lombok.experimental.UtilityClass;

/**
 * Parses an MPEG audio frame's header from its 32 bits (ISO/IEC 11172-3 section 2.4.2.3): the sync (11), version (2),
 * layer (2), protection bit, bitrate index (4), sample rate index (2), padding bit, the encoder's own private bit,
 * channel mode (2) and mode extension (2), then the copyright, original and emphasis fields.
 */
@UtilityClass
class FrameHeaderUtils {

    // A frame header's sync, its first 11 bits.
    private static final long SYNC = 0xFFE0_0000L;
    private static final int RESERVED_VERSION = 0b01;
    private static final int RESERVED_LAYER = 0b00;
    private static final int LAYER_III = 0b01;
    private static final int FREE_FORMAT_INDEX = 0;
    private static final int INVALID_BITRATE_INDEX = 15;
    private static final int RESERVED_SAMPLE_RATE_INDEX = 3;

    /**
     * Bytes that aren't a frame rarely start with a frame sync, so the other fields are only read when they do. Only
     * Layer III may be in free format, as FFmpeg finds no free-format frames at all.
     *
     * @return the header the bits make, or {@code NoHeader} when they don't make one
     */
    static ParsedHeader parseHeader(final long word) {
        if ((word & SYNC) != SYNC || hasReservedField(word)) {
            return new NoHeader();
        }
        final var version = MpegVersion.fromBits(field(word, 19, 2));
        final var layer = Layer.fromBits(field(word, 17, 2));
        final var protection = Protection.fromBit(field(word, 16, 1));
        final var bitrate = version.bitrate(layer, field(word, 12, 4));
        final var sampleRate = version.sampleRate(field(word, 10, 2));
        final var padding = field(word, 9, 1);
        final var channelMode = ChannelMode.values()[field(word, 6, 2)];
        final var modeExtension = field(word, 4, 2);
        return new HeaderFound(
                new FrameHeader(version, layer, protection, bitrate, sampleRate, padding, channelMode, modeExtension));
    }

    private static boolean hasReservedField(final long word) {
        final var layerBits = field(word, 17, 2);
        final var bitrateIndex = field(word, 12, 4);
        return field(word, 19, 2) == RESERVED_VERSION
                || layerBits == RESERVED_LAYER
                || bitrateIndex == INVALID_BITRATE_INDEX
                || field(word, 10, 2) == RESERVED_SAMPLE_RATE_INDEX
                || (bitrateIndex == FREE_FORMAT_INDEX && layerBits != LAYER_III);
    }

    private static int field(final long word, final int shift, final int size) {
        return (int) (word >>> shift) & ((1 << size) - 1);
    }
}
