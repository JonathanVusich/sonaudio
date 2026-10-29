package dev.javax.sonaudio.format.mp3;

import dev.javax.bitstream.BitInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.HexFormat;
import dev.javax.sonaudio.exception.InvalidFormatException;

/**
 * Builds the decoder's per-frame objects for tests.
 */
final class Mp3Fixtures {

    private Mp3Fixtures() {}

    /**
     * @param hex the header's four bytes, read as the parser reads a frame's
     */
    static FrameHeader header(final String hex) throws IOException, InvalidFormatException {
        final var bytes = HexFormat.of().parseHex(hex);
        return new Mp3Parser()
                .readAudioFrameHeader(BitInputStream.wrap(new ByteArrayInputStream(bytes), ByteOrder.BIG_ENDIAN));
    }

    /**
     * @return a short block, with no subblock gain
     */
    static Windowing.Switched shortBlock() {
        return new Windowing.Switched(BlockType.SHORT, LowSubbands.BY_BLOCK_TYPE, 0, 0, new int[] {0, 0, 0});
    }

    /**
     * @return a long block, coded with table 0
     */
    static Windowing.Normal longBlock() {
        return new Windowing.Normal(0, 0, 0, 0, 0);
    }

    /**
     * @return a granule's channel that is a long block, with no bits
     */
    static GranuleChannel longChannel() {
        return new GranuleChannel(0, 0, 0, 0, longBlock(), 0, 0, HuffmanTables.COUNT1_A, 0);
    }
}
