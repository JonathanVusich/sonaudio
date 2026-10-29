package dev.javax.sonaudio.format.mp3;

import dev.javax.bitstream.BitInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import java.util.List;
import java.util.stream.Stream;
import dev.javax.sonaudio.format.FieldUtils;
import dev.javax.sonaudio.format.mp3.Id3Frame.TextFrame;

/**
 * Reads the ID3v1 tag that may end an MP3: 128 bytes of fixed-size fields, which older taggers write. Its fields are
 * returned as the ID3v2.3 frames that hold the same data, so both tags map to Sona's tags alike.
 */
final class Id3v1Reader {

    static final int TAG_SIZE = 128;
    // "TAG"
    private static final long IDENTIFIER = 0x54_41_47;
    private static final int FIELD_SIZE = 30;
    private static final int YEAR_SIZE = 4;
    // ID3v1.1 ends the comment early with a NUL, and puts the track number in its last byte.
    private static final int COMMENT_SIZE = 28;

    /**
     * @param input the file's stream, at its last {@link #TAG_SIZE} bytes
     * @return the tag the file ends with, which is empty, with a size of 0, when it ends with none
     */
    Id3v1Tag readTag(final InputStream input) throws IOException {
        final var bits = BitInputStream.wrap(input, ByteOrder.BIG_ENDIAN);
        if (bits.readBits(24) != IDENTIFIER) {
            return new Id3v1Tag(0, List.of());
        }
        final var title = new TextFrame("TIT2", List.of(readField(bits)));
        final var artist = new TextFrame("TPE1", List.of(readField(bits)));
        final var album = new TextFrame("TALB", List.of(readField(bits)));
        // The bit stream holds no bits between fields, so the stream skips the year and comment.
        input.skipNBytes(YEAR_SIZE + COMMENT_SIZE);
        final var track = new TextFrame("TRCK", List.of(readTrack(bits)));
        final var frames = Stream.of(title, artist, album, track)
                .filter(frame -> !frame.values().getFirst().isEmpty())
                .toList();
        return new Id3v1Tag(TAG_SIZE, frames);
    }

    // Fields are padded with NULs or, by some taggers, spaces.
    private static String readField(final BitInputStream bits) throws IOException {
        return FieldUtils.readNulPaddedString(bits, FIELD_SIZE).strip();
    }

    // The track number follows the NUL that ends an ID3v1.1 comment, and is empty in an ID3v1.0 tag.
    private static String readTrack(final BitInputStream bits) throws IOException {
        final var commentEnd = bits.readBits(8);
        final var track = bits.readBits(8);
        if (commentEnd != 0 || track == 0) {
            return "";
        }
        return Long.toString(track);
    }
}
