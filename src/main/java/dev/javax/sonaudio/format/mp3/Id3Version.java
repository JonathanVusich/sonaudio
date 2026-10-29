package dev.javax.sonaudio.format.mp3;

import dev.javax.bitstream.BitInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;
import dev.javax.sonaudio.exception.InvalidFormatException;

/**
 * The ID3v2 versions, which lay out the same kinds of frames with different headers, sizes and flags (id3.org's
 * ID3v2.2, ID3v2.3.0 and ID3v2.4.0 structure documents).
 */
enum Id3Version {
    V2_2,
    V2_3,
    V2_4;

    // The tag header's unsynchronisation flag. In ID3v2.4, every frame carries the flag as well.
    private static final int UNSYNCHRONISED_TAG = 0x80;
    // ID3v2.2's compression flag, whose spec defines no scheme and says to skip such a tag. Later versions use the bit
    // for an extended header.
    private static final int V2_2_COMPRESSED_TAG = 0x40;
    private static final int EXTENDED_HEADER = 0x40;

    // Format flags, the second byte of a frame's flags, in ID3v2.3.0 section 3.3.1.
    private static final int V2_3_COMPRESSED = 0x80;
    private static final int V2_3_ENCRYPTED = 0x40;
    private static final int V2_3_GROUPED = 0x20;

    // Format flags in ID3v2.4.0 structure section 4.1.2.
    private static final int V2_4_GROUPED = 0x40;
    private static final int V2_4_COMPRESSED = 0x08;
    private static final int V2_4_ENCRYPTED = 0x04;
    private static final int V2_4_UNSYNCHRONISED = 0x02;
    private static final int V2_4_DATA_LENGTH = 0x01;

    // Frame IDs are capital letters and digits: three in ID3v2.2, and four after.
    private static final Pattern FRAME_ID = Pattern.compile("[A-Z0-9]+");
    private static final int V2_2_FRAME_ID_SIZE = 3;
    private static final int FRAME_ID_SIZE = 4;

    // The ID3v2.2 frames Sona reads, by the ID3v2.3 frames that replaced them, so both read alike.
    private static final Map<String, String> V2_2_FRAME_IDS = Map.of(
            "TT2", "TIT2",
            "TP1", "TPE1",
            "TP2", "TPE2",
            "TAL", "TALB",
            "TRK", "TRCK",
            "TPA", "TPOS",
            "TOR", "TORY",
            "TCM", "TCOM",
            "TXX", "TXXX",
            "UFI", "UFID");

    static Id3Version fromMajorVersion(final int majorVersion) throws InvalidFormatException {
        return switch (majorVersion) {
            case 2 -> V2_2;
            case 3 -> V2_3;
            case 4 -> V2_4;
            default -> throw new InvalidFormatException("ID3v2.%d tags aren't supported!".formatted(majorVersion));
        };
    }

    /**
     * @return whether the whole tag is compressed, which only ID3v2.2 defines, and with no scheme, so its spec says to
     *     skip the tag
     */
    boolean isCompressedTag(final int tagFlags) {
        return switch (this) {
            case V2_2 -> (tagFlags & V2_2_COMPRESSED_TAG) != 0;
            case V2_3, V2_4 -> false;
        };
    }

    /**
     * Reads the extended header's size field, if the tag has one.
     *
     * @return how many bytes of the extended header follow the field, or 0 without one
     */
    long readExtendedHeaderSize(final BitInputStream bits, final int tagFlags)
            throws IOException, InvalidFormatException {
        if ((tagFlags & EXTENDED_HEADER) == 0) {
            return 0;
        }
        return switch (this) {
            // ID3v2.2 has no extended header, and its bit says the tag is compressed.
            case V2_2 -> 0;
            // ID3v2.3's size leaves out the four bytes of the size itself, where ID3v2.4's counts them.
            case V2_3 -> bits.readBits(32);
            case V2_4 -> Id3Utils.readSynchsafeInt(bits) - Integer.BYTES;
        };
    }

    Id3FrameHeader readFrameHeader(final BitInputStream bits) throws IOException, InvalidFormatException {
        return switch (this) {
            case V2_2 -> new Id3FrameHeader(v22FrameId(readFrameId(bits, V2_2_FRAME_ID_SIZE)), bits.readBits(24), 0);
            case V2_3 -> new Id3FrameHeader(readFrameId(bits, FRAME_ID_SIZE), bits.readBits(32), readFormatFlags(bits));
            case V2_4 ->
                new Id3FrameHeader(
                        readFrameId(bits, FRAME_ID_SIZE), Id3Utils.readSynchsafeInt(bits), readFormatFlags(bits));
        };
    }

    boolean isEncrypted(final int formatFlags) {
        return switch (this) {
            case V2_2 -> false;
            case V2_3 -> (formatFlags & V2_3_ENCRYPTED) != 0;
            case V2_4 -> (formatFlags & V2_4_ENCRYPTED) != 0;
        };
    }

    /**
     * @return whether the frame's data is compressed with zlib
     */
    boolean isCompressed(final int formatFlags) {
        return switch (this) {
            case V2_2 -> false;
            case V2_3 -> (formatFlags & V2_3_COMPRESSED) != 0;
            case V2_4 -> (formatFlags & V2_4_COMPRESSED) != 0;
        };
    }

    /**
     * Reads the fields a frame's flags add before its data: ID3v2.3's inflated size and group ID, or ID3v2.4's group ID
     * and data length indicator.
     *
     * @return how many bytes the frame's data takes once inflated, which a compressed frame must give, otherwise 0
     */
    long readFrameFields(final BitInputStream bits, final int formatFlags) throws IOException, InvalidFormatException {
        return switch (this) {
            case V2_2 -> 0;
            case V2_3 -> readV23FrameFields(bits, formatFlags);
            case V2_4 -> readV24FrameFields(bits, formatFlags);
        };
    }

    /**
     * @return whether the tag's body is unsynchronised as a whole, frame headers included, as ID3v2.2 and 2.3 do it
     */
    Synchronisation bodySynchronisation(final int tagFlags) {
        return switch (this) {
            case V2_2, V2_3 -> synchronisation(tagFlags & UNSYNCHRONISED_TAG);
            case V2_4 -> Synchronisation.SYNCHRONISED;
        };
    }

    /**
     * @return whether a frame's data is unsynchronised, which ID3v2.4 says per frame, or for every frame of the tag
     */
    Synchronisation frameSynchronisation(final int tagFlags, final int formatFlags) {
        return switch (this) {
            case V2_2, V2_3 -> Synchronisation.SYNCHRONISED;
            case V2_4 -> synchronisation((formatFlags & V2_4_UNSYNCHRONISED) | (tagFlags & UNSYNCHRONISED_TAG));
        };
    }

    private static Synchronisation synchronisation(final int unsynchronisedFlags) {
        if (unsynchronisedFlags != 0) {
            return Synchronisation.UNSYNCHRONISED;
        }
        return Synchronisation.SYNCHRONISED;
    }

    private static String readFrameId(final BitInputStream bits, final int size)
            throws IOException, InvalidFormatException {
        final var frameId = new StringBuilder(size);
        for (var i = 0; i < size; i++) {
            frameId.append((char) bits.readBits(8));
        }
        if (!FRAME_ID.matcher(frameId).matches()) {
            throw new InvalidFormatException("ID3v2 frame ID '%s' isn't valid!".formatted(frameId));
        }
        return frameId.toString();
    }

    private static String v22FrameId(final String frameId) {
        return V2_2_FRAME_IDS.getOrDefault(frameId, frameId);
    }

    // The status flags come first, then the format flags.
    private static int readFormatFlags(final BitInputStream bits) throws IOException {
        bits.readBits(8);
        return (int) bits.readBits(8);
    }

    private static long readV23FrameFields(final BitInputStream bits, final int formatFlags) throws IOException {
        var inflatedSize = 0L;
        if ((formatFlags & V2_3_COMPRESSED) != 0) {
            inflatedSize = bits.readBits(32);
        }
        if ((formatFlags & V2_3_GROUPED) != 0) {
            bits.readBits(8);
        }
        return inflatedSize;
    }

    private static long readV24FrameFields(final BitInputStream bits, final int formatFlags)
            throws IOException, InvalidFormatException {
        if ((formatFlags & V2_4_GROUPED) != 0) {
            bits.readBits(8);
        }
        if ((formatFlags & V2_4_DATA_LENGTH) != 0) {
            return Id3Utils.readSynchsafeInt(bits);
        }
        return 0;
    }
}
