package dev.javax.sonaudio.format.mp3;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import dev.javax.sonaudio.exception.InvalidFormatException;

class BitReservoirTest {

    // A frame at 32 kbit/s has less main data than the 512 bytes the reservoir keeps for the frames after it.
    @Test
    void restartsWithFrameShorterThanBackstep() throws IOException, InvalidFormatException {
        final var header = Mp3Fixtures.header("FFFB1000");
        final var mainData = new ByteArrayInputStream(new byte[header.mainDataSize()]);

        assertThatCode(() -> new BitReservoir().restart(mainData, header)).doesNotThrowAnyException();
    }
}
