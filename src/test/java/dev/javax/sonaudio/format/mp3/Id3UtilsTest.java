package dev.javax.sonaudio.format.mp3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.javax.bitstream.BitInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import dev.javax.sonaudio.exception.InvalidFormatException;

class Id3UtilsTest {

    @Test
    void readsSynchsafeInteger() throws IOException, InvalidFormatException {
        // Seven bits from each byte: 7F7F7F7F is the largest, 2^28 - 1.
        assertThat(Id3Utils.readSynchsafeInt(bits("7F7F7F7F"))).isEqualTo((1 << 28) - 1);
        assertThat(Id3Utils.readSynchsafeInt(bits("00000219"))).isEqualTo(0x119);
        assertThat(Id3Utils.readSynchsafeInt(bits("00000101"))).isEqualTo(0x81);
        assertThat(Id3Utils.readSynchsafeInt(bits("01000000"))).isEqualTo(1 << 21);
    }

    @Test
    void rejectsSynchsafeIntegerWithTopBitSet() {
        assertThatThrownBy(() -> Id3Utils.readSynchsafeInt(bits("00000080")))
                .isInstanceOf(InvalidFormatException.class);
        assertThatThrownBy(() -> Id3Utils.readSynchsafeInt(bits("80000000")))
                .isInstanceOf(InvalidFormatException.class);
    }

    private static BitInputStream bits(final String hex) {
        return BitInputStream.wrap(new ByteArrayInputStream(HexFormat.of().parseHex(hex)), ByteOrder.BIG_ENDIAN);
    }
}
