package dev.javax.sonaudio.format.mp3;

import dev.javax.bitstream.BitInputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import dev.javax.sonaudio.exception.InvalidFormatException;

/**
 * How an ID3v2 frame encodes its text, given by the frame's first byte (ID3v2.4.0 structure section 4). Text is decoded
 * a character at a time as its bits are read.
 */
enum TextEncoding {
    ISO_8859_1,
    // Each string starts with its own byte order mark.
    UTF_16,
    // ID3v2.4 only, like UTF_8.
    UTF_16BE,
    UTF_8;

    private static final int BIG_ENDIAN_MARK = 0xFEFF;
    private static final int LITTLE_ENDIAN_MARK = 0xFFFE;

    static TextEncoding fromByte(final int encodingByte) throws InvalidFormatException {
        return switch (encodingByte) {
            case 0 -> ISO_8859_1;
            case 1 -> UTF_16;
            case 2 -> UTF_16BE;
            case 3 -> UTF_8;
            default -> throw new InvalidFormatException("Unknown ID3 text encoding: " + encodingByte);
        };
    }

    /**
     * Reads the strings that fill the rest of the region, which NUL terminators separate, and may end.
     */
    List<String> readStrings(final Id3Region region, final BitInputStream bits)
            throws IOException, InvalidFormatException {
        final var strings = new ArrayList<String>();
        while (region.hasMore()) {
            strings.add(readString(region, bits));
        }
        return List.copyOf(strings);
    }

    private String readString(final Id3Region region, final BitInputStream bits)
            throws IOException, InvalidFormatException {
        return switch (this) {
            case UTF_16 -> readUtf16String(region, bits);
            case ISO_8859_1, UTF_16BE, UTF_8 -> readString(region, bits, ByteOrder.BIG_ENDIAN);
        };
    }

    // Some taggers leave the byte order mark off an empty string, so it's only a terminator.
    private String readUtf16String(final Id3Region region, final BitInputStream bits)
            throws IOException, InvalidFormatException {
        return switch ((int) bits.readBits(16)) {
            case 0 -> "";
            case BIG_ENDIAN_MARK -> readString(region, bits, ByteOrder.BIG_ENDIAN);
            case LITTLE_ENDIAN_MARK -> readString(region, bits, ByteOrder.LITTLE_ENDIAN);
            default -> throw new InvalidFormatException("ID3 UTF-16 string has no byte order mark!");
        };
    }

    // The string ends at a NUL terminator, or at the end of the region.
    private String readString(final Id3Region region, final BitInputStream bits, final ByteOrder byteOrder)
            throws IOException, InvalidFormatException {
        final var string = new StringBuilder();
        while (region.hasMore()) {
            final var codePoint = readCodePoint(bits, byteOrder);
            if (codePoint == 0) {
                break;
            }
            string.appendCodePoint(codePoint);
        }
        return string.toString();
    }

    private int readCodePoint(final BitInputStream bits, final ByteOrder byteOrder)
            throws IOException, InvalidFormatException {
        return switch (this) {
            case ISO_8859_1 -> (int) bits.readBits(8);
            case UTF_16, UTF_16BE -> readUtf16CodeUnit(bits, byteOrder);
            case UTF_8 -> readUtf8CodePoint(bits);
        };
    }

    // A surrogate pair is read a unit at a time, and the string joins them.
    private static int readUtf16CodeUnit(final BitInputStream bits, final ByteOrder byteOrder) throws IOException {
        final var codeUnit = (char) bits.readBits(16);
        if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
            return Character.reverseBytes(codeUnit);
        }
        return codeUnit;
    }

    // The lead byte's run of 1 bits says how many bytes the character takes, and each byte after it starts with 10.
    private static int readUtf8CodePoint(final BitInputStream bits) throws IOException, InvalidFormatException {
        final var length = bits.countOnes();
        if (length == 1 || length > 4) {
            throw new InvalidFormatException("ID3 text isn't valid UTF-8!");
        }
        bits.readBits(1);
        var codePoint = (int) bits.readBits(7 - length);
        for (var i = 1; i < length; i++) {
            if (bits.readBits(2) != 0b10) {
                throw new InvalidFormatException("ID3 text isn't valid UTF-8!");
            }
            codePoint = codePoint << 6 | (int) bits.readBits(6);
        }
        return codePoint;
    }
}
