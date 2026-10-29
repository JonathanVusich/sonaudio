package dev.javax.sonaudio.format.mp3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.javax.bitstream.BitInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import dev.javax.sonaudio.exception.InvalidFormatException;

class TextEncodingTest {

    @Test
    void readsNulSeparatedStrings() throws IOException, InvalidFormatException {
        final var bytes = "Coldplay\0Guy Berryman\0".getBytes(StandardCharsets.UTF_8);

        assertThat(readStrings(bytes, TextEncoding.UTF_8)).containsExactly("Coldplay", "Guy Berryman");
    }

    @Test
    void readsMultiByteUtf8() throws IOException, InvalidFormatException {
        final var bytes = "ÿàn €𝄞".getBytes(StandardCharsets.UTF_8);

        assertThat(readStrings(bytes, TextEncoding.UTF_8)).containsExactly("ÿàn €𝄞");
    }

    @Test
    void readsIso88591() throws IOException, InvalidFormatException {
        final var bytes = "Ünïcode".getBytes(StandardCharsets.ISO_8859_1);

        assertThat(readStrings(bytes, TextEncoding.ISO_8859_1)).containsExactly("Ünïcode");
    }

    @Test
    void readsUtf16StringsWithTheirOwnByteOrderMarks() throws IOException, InvalidFormatException {
        final var bytes = new ByteArrayOutputStream();
        bytes.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xFE});
        bytes.writeBytes("Ünïcode𝄞".getBytes(StandardCharsets.UTF_16LE));
        // The terminator, then the second string's byte order mark.
        bytes.writeBytes(new byte[] {0, 0, (byte) 0xFE, (byte) 0xFF});
        bytes.writeBytes("Second".getBytes(StandardCharsets.UTF_16BE));

        assertThat(readStrings(bytes.toByteArray(), TextEncoding.UTF_16)).containsExactly("Ünïcode𝄞", "Second");
    }

    @Test
    void readsUtf16EmptyStringWithoutByteOrderMark() throws IOException, InvalidFormatException {
        final var bytes = new byte[] {0, 0, (byte) 0xFE, (byte) 0xFF, 0, 0x41};

        assertThat(readStrings(bytes, TextEncoding.UTF_16)).containsExactly("", "A");
    }

    @Test
    void utf16TerminatorFallsOnCharacterBoundary() throws IOException, InvalidFormatException {
        // "Ā" is 0x0100 and "A" is 0x0041: the zero bytes between them don't end the string.
        final var bytes = "ĀA".getBytes(StandardCharsets.UTF_16BE);

        assertThat(readStrings(bytes, TextEncoding.UTF_16BE)).containsExactly("ĀA");
    }

    @Test
    void rejectsInvalidUtf8() {
        // A continuation byte can't start a character.
        assertThatThrownBy(() -> readStrings(new byte[] {(byte) 0x80}, TextEncoding.UTF_8))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void rejectsUtf16StringWithoutByteOrderMark() {
        assertThatThrownBy(() -> readStrings(new byte[] {0, 0x41}, TextEncoding.UTF_16))
                .isInstanceOf(InvalidFormatException.class);
    }

    private static List<String> readStrings(final byte[] bytes, final TextEncoding encoding)
            throws IOException, InvalidFormatException {
        final var region = new Id3Region(new ByteArrayInputStream(bytes), bytes.length, Synchronisation.SYNCHRONISED);
        return encoding.readStrings(region, BitInputStream.wrap(region, ByteOrder.BIG_ENDIAN));
    }
}
