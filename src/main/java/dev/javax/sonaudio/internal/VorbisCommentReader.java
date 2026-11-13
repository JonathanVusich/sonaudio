package dev.javax.sonaudio.internal;

import dev.javax.sonaudio.exception.InvalidFormatException;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a Vorbis comment, which FLAC wraps in a metadata block and Opus in its {@code OpusTags} packet. A malformed
 * one throws {@link InvalidFormatException}.
 */
public final class VorbisCommentReader {

    /**
     * Makes a reader, which keeps no state between comments.
     */
    public VorbisCommentReader() {}

    /**
     * Reads a comment from the stream, which this reads no more of than the comment holds. The vendor's and the
     * fields' lengths and the field count are little-endian u32s.
     *
     * @param inputStream the stream, at the vendor's length
     * @param size how many bytes the comment takes, which no length in it may exceed
     * @return the comment's vendor and fields
     * @throws IOException when the stream can't be read
     * @throws InvalidFormatException when a length or the field count doesn't fit in the comment, or a field has no
     *     {@code =}
     */
    public VorbisComments readVorbisComments(final InputStream inputStream, final int size)
            throws IOException, InvalidFormatException {
        final var dataStream = new DataInputStream(inputStream);
        final var length = Integer.reverseBytes(dataStream.readInt());
        final var vendor = readUtf8(dataStream, length, size);
        final var numberOfFields = Integer.reverseBytes(dataStream.readInt());
        // Each field starts with its 4-byte length, so more fields than that can't fit in the comment.
        if (numberOfFields < 0 || numberOfFields > size / 4) {
            throw new InvalidFormatException("Incorrect number of Vorbis comment fields: " + numberOfFields);
        }
        final var fields = new ArrayList<VorbisField>(numberOfFields);
        for (var i = 0; i < numberOfFields; i++) {
            fields.add(readVorbisField(dataStream, size));
        }
        return new VorbisComments(vendor, List.copyOf(fields));
    }

    private VorbisField readVorbisField(final DataInputStream dataStream, final int size)
            throws IOException, InvalidFormatException {
        final var fieldLength = Integer.reverseBytes(dataStream.readInt());
        final var fieldContent = readUtf8(dataStream, fieldLength, size);
        final var nameEnd = fieldContent.indexOf("=");
        if (nameEnd < 0) {
            throw new InvalidFormatException("Vorbis comment field without a '=': " + fieldContent);
        }
        final var name = fieldContent.substring(0, nameEnd);
        final var content = fieldContent.substring(nameEnd + 1);
        return new VorbisField(name, content);
    }

    private String readUtf8(final DataInputStream dataStream, final int length, final int size)
            throws IOException, InvalidFormatException {
        if (length < 0 || length > size) {
            throw new InvalidFormatException(length + " bytes in a Vorbis comment of " + size);
        }
        return new String(dataStream.readNBytes(length), StandardCharsets.UTF_8);
    }
}
