package dev.javax.sonaudio.format.mp3;

import dev.javax.sonaudio.format.mp3.ParsedHeader.HeaderFound;
import dev.javax.sonaudio.format.mp3.ParsedHeader.NoHeader;
import java.util.HexFormat;

/**
 * Builds the decoder's per-frame objects for tests.
 */
final class Mp3Fixtures {

    private Mp3Fixtures() {}

    /**
     * @param hex the header's four bytes, parsed as the parser parses a frame's
     */
    static FrameHeader header(final String hex) {
        return switch (FrameHeaderUtils.parseHeader(HexFormat.fromHexDigitsToLong(hex))) {
            case HeaderFound found -> found.header();
            case NoHeader _ -> throw new AssertionError(hex + " isn't a frame header");
        };
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
