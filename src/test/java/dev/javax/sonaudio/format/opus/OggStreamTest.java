package dev.javax.sonaudio.format.opus;

import static dev.javax.sonaudio.format.opus.OggTestFiles.BEGINNING;
import static dev.javax.sonaudio.format.opus.OggTestFiles.CONTINUED;
import static dev.javax.sonaudio.format.opus.OggTestFiles.END;
import static dev.javax.sonaudio.format.opus.OggTestFiles.NO_GRANULE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.javax.sonaudio.exception.InvalidFormatException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class OggStreamTest {

    private static final int SERIAL = 7;
    private static final int PRE_SKIP = 312;
    // A SILK narrowband packet of 20 ms: 960 samples at 48 kHz.
    private static final int TWENTY_MILLISECONDS = 1;

    @Test
    void dropsThePreSkipFromTheFirstPacketOnly() throws IOException, InvalidFormatException {
        final var file = OggTestFiles.opusFile(SERIAL, PRE_SKIP, 960 * 3, packet(), packet(), packet());

        final var packets = audioPackets(file);

        assertThat(packets).extracting(OggPacket::startTrim).containsExactly(PRE_SKIP, 0, 0);
        assertThat(packets).extracting(OggPacket::endTrim).containsExactly(0, 0, 0);
    }

    @Test
    void trimsTheLastPacketByHowFarItPassesTheLastGranulePosition() throws IOException, InvalidFormatException {
        // Three packets are 2880 samples, so a final granule position of 2780 leaves 100 to drop.
        final var file = OggTestFiles.opusFile(SERIAL, PRE_SKIP, 2780, packet(), packet(), packet());

        final var packets = audioPackets(file);

        assertThat(packets).extracting(OggPacket::endTrim).containsExactly(0, 0, 100);
    }

    @Test
    void trimsAtMostTheLastPacket() throws IOException, InvalidFormatException {
        final var file = OggTestFiles.opusFile(SERIAL, PRE_SKIP, 1000, packet(), packet(), packet());

        final var packets = audioPackets(file);

        assertThat(packets).extracting(OggPacket::endTrim).containsExactly(0, 0, 960);
    }

    @Test
    void timesPacketsOfOnePageFromItsGranulePosition() throws IOException, InvalidFormatException {
        // Both packets are on the one page that ends the stream, so FFmpeg counts them from the start of the stream,
        // and with a granule position of 1900 the 1920 samples they hold are 20 too many.
        final var file = OggTestFiles.concatenate(
                OggTestFiles.packetPage(BEGINNING, 0, SERIAL, 0, OggTestFiles.opusHead(1, PRE_SKIP, 0)),
                OggTestFiles.packetPage(0, 0, SERIAL, 1, OggTestFiles.opusTags()),
                OggTestFiles.packetPage(END, 1900, SERIAL, 2, packet(), packet()));

        final var packets = audioPackets(file);

        assertThat(packets).extracting(OggPacket::endTrim).containsExactly(0, 20);
    }

    @Test
    void putsAPacketThatCrossesPagesBackTogether() throws IOException, InvalidFormatException {
        final var big = OggTestFiles.audioPacket(TWENTY_MILLISECONDS, 299);
        final var firstPart = Arrays.copyOfRange(big, 0, 255);
        final var secondPart = Arrays.copyOfRange(big, 255, big.length);
        final var file = OggTestFiles.concatenate(
                OggTestFiles.packetPage(BEGINNING, 0, SERIAL, 0, OggTestFiles.opusHead(1, 0, 0)),
                OggTestFiles.packetPage(0, 0, SERIAL, 1, OggTestFiles.opusTags()),
                OggTestFiles.page(0, NO_GRANULE, SERIAL, 2, new byte[] {(byte) 255}, firstPart),
                OggTestFiles.page(CONTINUED | END, 960, SERIAL, 3, new byte[] {(byte) secondPart.length}, secondPart));

        final var packets = audioPackets(file);

        assertThat(packets).hasSize(1);
        assertThat(packets.getFirst().bytes()).isEqualTo(big);
    }

    @Test
    void skipsThePagesOfOtherStreams() throws IOException, InvalidFormatException {
        final var file = OggTestFiles.concatenate(
                OggTestFiles.packetPage(BEGINNING, 0, SERIAL, 0, OggTestFiles.opusHead(1, PRE_SKIP, 0)),
                OggTestFiles.packetPage(BEGINNING, 0, 99, 0, new byte[] {1, 2, 3}),
                OggTestFiles.packetPage(0, 0, SERIAL, 1, OggTestFiles.opusTags()),
                OggTestFiles.packetPage(0, 0, 99, 1, new byte[] {4, 5}),
                OggTestFiles.packetPage(END, 960, SERIAL, 2, packet()));

        assertThat(audioPackets(file)).hasSize(1);
    }

    @Test
    void rejectsAStreamThatStartsAfterTheAudioHas() throws IOException, InvalidFormatException {
        final var file = OggTestFiles.concatenate(
                OggTestFiles.opusFile(SERIAL, PRE_SKIP, 960, packet()),
                OggTestFiles.packetPage(BEGINNING, 0, 8, 0, OggTestFiles.opusHead(1, PRE_SKIP, 0)));
        final var stream = streamAtAudio(file);
        assertThat(stream.hasNextPacket()).isTrue();
        stream.nextAudioPacket();

        assertThatThrownBy(stream::hasNextPacket)
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("Chained");
    }

    @Test
    void rejectsAnEmptyPacket() throws IOException, InvalidFormatException {
        final var file = OggTestFiles.concatenate(
                OggTestFiles.packetPage(BEGINNING, 0, SERIAL, 0, OggTestFiles.opusHead(1, PRE_SKIP, 0)),
                OggTestFiles.packetPage(0, 0, SERIAL, 1, OggTestFiles.opusTags()),
                OggTestFiles.packetPage(END, 960, SERIAL, 2, new byte[0]));
        final var stream = streamAtAudio(file);

        assertThat(stream.hasNextPacket()).isTrue();
        assertThatThrownBy(stream::nextAudioPacket).isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void rejectsAContinuationOfNothing() throws IOException, InvalidFormatException {
        final var file = OggTestFiles.concatenate(
                OggTestFiles.packetPage(BEGINNING, 0, SERIAL, 0, OggTestFiles.opusHead(1, PRE_SKIP, 0)),
                OggTestFiles.packetPage(0, 0, SERIAL, 1, OggTestFiles.opusTags()),
                OggTestFiles.packetPage(CONTINUED, 960, SERIAL, 2, packet()));
        final var stream = streamAtAudio(file);

        assertThatThrownBy(stream::hasNextPacket)
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("continuation");
    }

    @Test
    void rejectsAFileThatEndsInsideAPacket() throws IOException, InvalidFormatException {
        final var file = OggTestFiles.concatenate(
                OggTestFiles.packetPage(BEGINNING, 0, SERIAL, 0, OggTestFiles.opusHead(1, PRE_SKIP, 0)),
                OggTestFiles.packetPage(0, 0, SERIAL, 1, OggTestFiles.opusTags()),
                OggTestFiles.page(0, NO_GRANULE, SERIAL, 2, new byte[] {(byte) 255}, new byte[255]));
        final var stream = streamAtAudio(file);

        assertThatThrownBy(stream::hasNextPacket)
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("inside a packet");
    }

    private byte[] packet() {
        return OggTestFiles.audioPacket(TWENTY_MILLISECONDS, 10);
    }

    private OggStream streamAtAudio(final byte[] file) throws IOException, InvalidFormatException {
        final var stream = new OggStream(new OpusParser(), new ByteArrayInputStream(file), SERIAL, PRE_SKIP);
        stream.nextHeaderPacket();
        stream.nextHeaderPacket();
        return stream;
    }

    private List<OggPacket> audioPackets(final byte[] file) throws IOException, InvalidFormatException {
        final var stream = streamAtAudio(file);
        final var packets = new ArrayList<OggPacket>();
        while (stream.hasNextPacket()) {
            packets.add(stream.nextAudioPacket());
        }
        return packets;
    }
}
