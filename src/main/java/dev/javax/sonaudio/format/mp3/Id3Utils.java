package dev.javax.sonaudio.format.mp3;

import dev.javax.bitstream.BitInputStream;
import java.io.IOException;
import lombok.experimental.UtilityClass;
import dev.javax.sonaudio.exception.InvalidFormatException;

@UtilityClass
final class Id3Utils {

    // The top bit of each byte of a synchsafe integer.
    private static final int TOP_BITS = 0x80808080;

    /**
     * Reads a synchsafe integer: four bytes whose top bits are 0, so it can't be read as a frame sync, and whose other
     * seven bits each hold the value (ID3v2.4.0 structure section 6.2).
     *
     * @throws InvalidFormatException when a byte's top bit is set
     */
    static int readSynchsafeInt(final BitInputStream bits) throws IOException, InvalidFormatException {
        final var bytes = (int) bits.readBits(32);
        if ((bytes & TOP_BITS) != 0) {
            throw new InvalidFormatException("ID3v2 synchsafe integer has a byte with its top bit set!");
        }
        return (bytes & 0x7F) | (bytes >> 1 & 0x3F80) | (bytes >> 2 & 0x1F_C000) | (bytes >> 3 & 0xFE0_0000);
    }
}
