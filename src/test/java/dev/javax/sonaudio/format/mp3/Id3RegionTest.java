package dev.javax.sonaudio.format.mp3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class Id3RegionTest {

    @Test
    void readsOnlyItsSize() throws IOException {
        final var input = stream("0102030405");
        final var region = new Id3Region(input, 3, Synchronisation.SYNCHRONISED);

        assertThat(region.readAllBytes()).isEqualTo(HexFormat.of().parseHex("010203"));
        assertThat(region.hasMore()).isFalse();
        assertThat(input.read()).isEqualTo(4);
    }

    @Test
    void unsynchronisedRegionDropsTheZeroAfterEach0xFF() throws IOException {
        final var region = new Id3Region(stream("FF00E001FF0000FF41"), 9, Synchronisation.UNSYNCHRONISED);

        assertThat(region.readAllBytes()).isEqualTo(HexFormat.of().parseHex("FFE001FF00FF41"));
    }

    // The zero after the region's last byte is dropped before the region says it has more.
    @Test
    void unsynchronisedRegionEndsAfterItsLastInsertedZero() throws IOException {
        final var region = new Id3Region(stream("41FF00"), 3, Synchronisation.UNSYNCHRONISED);

        assertThat(region.read()).isEqualTo(0x41);
        assertThat(region.read()).isEqualTo(0xFF);
        assertThat(region.hasMore()).isFalse();
    }

    @Test
    void synchronisedRegionKeepsZerosAfter0xFF() throws IOException {
        final var region = new Id3Region(stream("FF00"), 2, Synchronisation.SYNCHRONISED);

        assertThat(region.readAllBytes()).isEqualTo(HexFormat.of().parseHex("FF00"));
    }

    @Test
    void skipsWhatItReads() throws IOException {
        final var region = new Id3Region(stream("FF0041FF0042"), 6, Synchronisation.UNSYNCHRONISED);
        region.skipNBytes(3);

        assertThat(region.peek()).isEqualTo(0x42);
    }

    @Test
    void rejectsRegionLongerThanStream() {
        assertThatThrownBy(() -> new Id3Region(stream("01"), 2, Synchronisation.SYNCHRONISED).readAllBytes())
                .isInstanceOf(EOFException.class);
    }

    private static ByteArrayInputStream stream(final String hex) {
        return new ByteArrayInputStream(HexFormat.of().parseHex(hex));
    }
}
