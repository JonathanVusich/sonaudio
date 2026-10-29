package dev.javax.sonaudio.format;

import dev.javax.bitstream.BitInputStream;
import java.io.IOException;
import lombok.experimental.UtilityClass;

@UtilityClass
public final class FieldUtils {

    /**
     * Reads a fixed-size field of single-byte text (ASCII or ISO-8859-1), which fills the field or ends early with a
     * NUL, followed by padding.
     *
     * @return the field's text, up to its first NUL
     */
    public static String readNulPaddedString(final BitInputStream bits, final int size) throws IOException {
        final var field = new StringBuilder(size);
        for (var i = 0; i < size; i++) {
            field.append((char) bits.readBits(8));
        }
        final var nul = field.indexOf("\0");
        if (nul >= 0) {
            field.setLength(nul);
        }
        return field.toString();
    }
}
