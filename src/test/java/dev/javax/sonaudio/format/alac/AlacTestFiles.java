package dev.javax.sonaudio.format.alac;

import dev.javax.sonaudio.samples.AlacSample;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import lombok.experimental.UtilityClass;

/**
 * The fixtures' bytes, and what changes tests make to them before they parse them. The changes rename boxes, or resize
 * the boxes of a file whose {@code moov} box is last, as the fixtures {@code STEREO16_LEVEL1} and
 * {@code MOOV_AFTER_MDAT} are, so that the positions of its audio don't move.
 */
@UtilityClass
final class AlacTestFiles {

    /**
     * @return the sample's file, which the caller may change
     */
    static byte[] bytes(final AlacSample sample) throws IOException {
        try (final var inputStream = sample.inputStream()) {
            return inputStream.readAllBytes();
        }
    }

    /**
     * @return where the first packet is: the start of the {@code mdat} box's payload, which the fixtures' audio
     *     starts at
     */
    static int firstPacketOffset(final byte[] file) {
        return indexOf(file, "mdat", 0) + 4;
    }

    /**
     * @return the position of the four characters in the file, from the position on
     */
    static int indexOf(final byte[] file, final String fourcc, final int from) {
        final var wanted = fourcc.getBytes(StandardCharsets.ISO_8859_1);
        for (var position = from; position + wanted.length <= file.length; position++) {
            if (matches(file, position, wanted)) {
                return position;
            }
        }
        throw new IllegalArgumentException("No " + fourcc + " in the file");
    }

    /**
     * @return the position of the last occurrence of the four characters that starts before the position
     */
    static int lastIndexOf(final byte[] file, final String fourcc, final int before) {
        final var wanted = fourcc.getBytes(StandardCharsets.ISO_8859_1);
        for (var position = Math.min(before, file.length - wanted.length); position >= 0; position--) {
            if (matches(file, position, wanted)) {
                return position;
            }
        }
        throw new IllegalArgumentException("No " + fourcc + " in the file");
    }

    private static boolean matches(final byte[] file, final int position, final byte[] wanted) {
        for (var i = 0; i < wanted.length; i++) {
            if (file[position + i] != wanted[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Renames the boxes of a type in the file's {@code moov} box, which keeps every size valid.
     */
    static byte[] renamed(final byte[] file, final String from, final String to) {
        final var changed = file.clone();
        final var moov = lastIndexOf(file, "moov", file.length);
        final var replacement = to.getBytes(StandardCharsets.ISO_8859_1);
        var position = moov;
        while (position < file.length - 4) {
            if (matches(file, position, from.getBytes(StandardCharsets.ISO_8859_1))) {
                System.arraycopy(replacement, 0, changed, position, replacement.length);
            }
            position++;
        }
        return changed;
    }

    /**
     * Replaces the file's edit list, which has one entry, with these: a duration in the movie's time scale and a
     * media time each.
     */
    static byte[] withEditList(final byte[] file, final long[][] entries) throws IOException {
        final var elst = lastIndexOf(file, "elst", file.length) - 4;
        final var oldSize = ByteBuffer.wrap(file).getInt(elst);
        final var box = new ByteArrayOutputStream();
        final var out = new DataOutputStream(box);
        out.writeInt(16 + 12 * entries.length);
        out.writeBytes("elst");
        out.writeInt(0);
        out.writeInt(entries.length);
        for (final var entry : entries) {
            out.writeInt((int) entry[0]);
            out.writeInt((int) entry[1]);
            out.writeInt(0x10000);
        }
        final var delta = box.size() - oldSize;
        final var result = new ByteArrayOutputStream();
        result.write(file, 0, elst);
        result.write(box.toByteArray());
        result.write(file, elst + oldSize, file.length - elst - oldSize);
        final var bytes = result.toByteArray();
        grow(bytes, "edts", elst, delta);
        grow(bytes, "trak", elst, delta);
        grow(bytes, "moov", elst, delta);
        return bytes;
    }

    /**
     * Sets the track's duration in its {@code mdhd} box, which is in the media's time scale.
     */
    static byte[] withMediaDuration(final byte[] file, final int duration) {
        final var changed = file.clone();
        final var mdhd = lastIndexOf(file, "mdhd", file.length);
        // After the type come the version and flags, the creation and modification times and the time scale.
        ByteBuffer.wrap(changed).putInt(mdhd + 4 + 4 + 4 + 4 + 4, duration);
        return changed;
    }

    /**
     * Adds a freeform ({@code ----}) item to the file's {@code ilst} box, which is at the end of the file.
     */
    static byte[] withFreeformItem(final byte[] file, final String name, final String value) throws IOException {
        final var item = freeformItem(name, value);
        final var result = new ByteArrayOutputStream();
        result.write(file);
        result.write(item);
        final var bytes = result.toByteArray();
        for (final var parent : new String[] {"ilst", "meta", "udta", "moov"}) {
            grow(bytes, parent, file.length, item.length);
        }
        return bytes;
    }

    private static byte[] freeformItem(final String name, final String value) throws IOException {
        final var mean = fullBox("mean", "com.apple.iTunes".getBytes(StandardCharsets.UTF_8));
        final var nameBox = fullBox("name", name.getBytes(StandardCharsets.UTF_8));
        final var dataBox = new ByteArrayOutputStream();
        final var data = new DataOutputStream(dataBox);
        data.writeInt(1);
        data.writeInt(0);
        data.write(value.getBytes(StandardCharsets.UTF_8));
        final var dataWithHeader = box("data", dataBox.toByteArray());
        final var content = new ByteArrayOutputStream();
        content.write(mean);
        content.write(nameBox);
        content.write(dataWithHeader);
        return box("----", content.toByteArray());
    }

    private static byte[] fullBox(final String type, final byte[] content) throws IOException {
        final var withFlags = new ByteArrayOutputStream();
        withFlags.write(new byte[4]);
        withFlags.write(content);
        return box(type, withFlags.toByteArray());
    }

    private static byte[] box(final String type, final byte[] content) throws IOException {
        final var out = new ByteArrayOutputStream();
        final var data = new DataOutputStream(out);
        data.writeInt(8 + content.length);
        data.writeBytes(type);
        data.write(content);
        return out.toByteArray();
    }

    // Makes the last box of the type before the position longer.
    private static void grow(final byte[] file, final String type, final int before, final int delta) {
        final var start = lastIndexOf(file, type, before) - 4;
        final var buffer = ByteBuffer.wrap(file);
        buffer.putInt(start, buffer.getInt(start) + delta);
    }

    /**
     * Gives the sample entry the version QuickTime's sound description has, which comes with 16 more bytes of fields
     * in version 1 and 36 in version 2, before the entry's boxes.
     */
    static byte[] withSoundDescriptionVersion(final byte[] file, final int version) throws IOException {
        final var cookie = lastIndexOf(file, "alac", file.length);
        final var entry = lastIndexOf(file, "alac", cookie - 1);
        final var extra = new byte[versionFieldsSize(version)];
        final var bytes = inserted(file, cookie - 4, extra);
        ByteBuffer.wrap(bytes).putShort(entry + 4 + 8, (short) version);
        growSampleEntry(bytes, entry, extra.length);
        return bytes;
    }

    private static int versionFieldsSize(final int version) {
        return switch (version) {
            case 1 -> 16;
            case 2 -> 36;
            default -> throw new IllegalArgumentException("No fields for version " + version);
        };
    }

    /**
     * Wraps the {@code alac} box in a {@code wave} box, as QuickTime does: an {@code frma} box of the format, the
     * {@code alac} box and an empty box at the end.
     */
    static byte[] withWaveAroundCookie(final byte[] file) throws IOException {
        final var cookie = lastIndexOf(file, "alac", file.length) - 4;
        final var cookieBox = Arrays.copyOfRange(file, cookie, cookie + 36);
        final var content = new ByteArrayOutputStream();
        content.write(box("frma", "alac".getBytes(StandardCharsets.ISO_8859_1)));
        content.write(cookieBox);
        content.write(box("\0\0\0\0", new byte[0]));
        return replacedCookie(file, box("wave", content.toByteArray()));
    }

    /**
     * Replaces the {@code alac} box with a {@code wave} box that holds the cookie's 24 bytes alone.
     */
    static byte[] withWaveOfRawCookie(final byte[] file) throws IOException {
        final var cookie = lastIndexOf(file, "alac", file.length) - 4;
        // The box's size, type and version come before the 24 bytes of the cookie.
        final var raw = Arrays.copyOfRange(file, cookie + 12, cookie + 36);
        return replacedCookie(file, box("wave", raw));
    }

    private static byte[] replacedCookie(final byte[] file, final byte[] replacement) throws IOException {
        final var cookieType = lastIndexOf(file, "alac", file.length);
        final var entry = lastIndexOf(file, "alac", cookieType - 1);
        final var out = new ByteArrayOutputStream();
        out.write(file, 0, cookieType - 4);
        out.write(replacement);
        out.write(file, cookieType - 4 + 36, file.length - cookieType + 4 - 36);
        final var bytes = out.toByteArray();
        growSampleEntry(bytes, entry, replacement.length - 36);
        return bytes;
    }

    // Makes the sample entry, which starts at the position of its type, and the boxes that hold it longer.
    private static void growSampleEntry(final byte[] bytes, final int entryType, final int delta) {
        grow(bytes, "alac", entryType + 1, delta);
        for (final var parent : new String[] {"stsd", "stbl", "minf", "mdia", "trak", "moov"}) {
            grow(bytes, parent, entryType, delta);
        }
    }

    private static byte[] inserted(final byte[] file, final int position, final byte[] inserted) {
        final var result = new byte[file.length + inserted.length];
        System.arraycopy(file, 0, result, 0, position);
        System.arraycopy(inserted, 0, result, position, inserted.length);
        System.arraycopy(file, position, result, position + inserted.length, file.length - position);
        return result;
    }

    /**
     * Gives the {@code moov} box of a file that has it last a 64-bit size, which makes its header 8 bytes longer.
     */
    static byte[] withLargeMoovSize(final byte[] file) throws IOException {
        final var moov = lastIndexOf(file, "moov", file.length) - 4;
        final var size = ByteBuffer.wrap(file).getInt(moov);
        final var out = new ByteArrayOutputStream();
        final var data = new DataOutputStream(out);
        data.write(file, 0, moov);
        data.writeInt(1);
        data.writeBytes("moov");
        data.writeLong(size + 8L);
        data.write(file, moov + 8, file.length - moov - 8);
        return out.toByteArray();
    }

    /**
     * Gives the {@code moov} box of a file that has it last a size of 0, which says that it goes on to the file's end.
     */
    static byte[] withMoovToTheEnd(final byte[] file) {
        final var changed = file.clone();
        ByteBuffer.wrap(changed).putInt(lastIndexOf(file, "moov", file.length) - 4, 0);
        return changed;
    }
}
