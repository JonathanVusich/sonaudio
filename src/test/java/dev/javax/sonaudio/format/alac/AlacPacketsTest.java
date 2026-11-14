package dev.javax.sonaudio.format.alac;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.BitSet;
import org.junit.jupiter.api.Test;

class AlacPacketsTest {

    // Three packets of 100 samples, the last of which is dropped, with the padding given for each.
    private static AlacPackets packets(final long initialSkip, final int... paddings) {
        final var dropped = new BitSet();
        dropped.set(2);
        return new AlacPackets(
                new long[] {10, 20, 30},
                new int[] {5, 6, 7},
                new long[] {100, 100, 100},
                dropped,
                paddings,
                initialSkip);
    }

    @Test
    void givesWhereEachPacketIs() {
        final var packets = packets(0, 0, 0, 0);

        assertThat(packets.count()).isEqualTo(3);
        assertThat(packets.offset(1)).isEqualTo(20);
        assertThat(packets.size(2)).isEqualTo(7);
    }

    @Test
    void keepsAPacketWhole() {
        assertThat(packets(0, 0, 0, 0).trim(0, 100, 0)).isEqualTo(new PacketTrim(0, 100, 0));
    }

    @Test
    void cutsTheSkippedStartOffAPacket() {
        assertThat(packets(30, 0, 0, 0).trim(0, 100, 30)).isEqualTo(new PacketTrim(30, 70, 0));
    }

    @Test
    void dropsAPacketThatTheSkipCoversWholly() {
        assertThat(packets(0, 0, 0, 0).trim(0, 100, 100)).isEqualTo(new PacketTrim(0, 0, 0));
        assertThat(packets(0, 0, 0, 0).trim(0, 100, 250)).isEqualTo(new PacketTrim(0, 0, 150));
    }

    @Test
    void takesAPacketsSamplesFromWhatIsLeftToSkipWhenItIsDropped() {
        assertThat(packets(0, 0, 0, 0).trim(2, 100, 130)).isEqualTo(new PacketTrim(0, 0, 30));
        assertThat(packets(0, 0, 0, 0).trim(2, 100, 20)).isEqualTo(new PacketTrim(0, 0, 0));
    }

    @Test
    void doesNotPlayADroppedPacketWhenNothingIsLeftToSkip() {
        assertThat(packets(0, 0, 0, 0).trim(2, 100, 0)).isEqualTo(new PacketTrim(0, 0, 0));
    }

    @Test
    void cutsTheEndPaddingOffAPacket() {
        assertThat(packets(0, 0, 40, 0).trim(1, 100, 0)).isEqualTo(new PacketTrim(0, 60, 0));
    }

    @Test
    void cutsTheEndPaddingOffWhatIsLeftAfterTheSkip() {
        assertThat(packets(10, 0, 40, 0).trim(1, 100, 10)).isEqualTo(new PacketTrim(10, 50, 0));
    }

    @Test
    void dropsAPacketThatThePaddingCoversWholly() {
        assertThat(packets(0, 0, 100, 0).trim(1, 100, 0)).isEqualTo(new PacketTrim(0, 0, 0));
    }

    // FFmpeg's decoder doesn't cut more than the frame has.
    @Test
    void ignoresPaddingOfMoreThanThePacketHas() {
        assertThat(packets(0, 0, 101, 0).trim(1, 100, 0)).isEqualTo(new PacketTrim(0, 100, 0));
    }

    @Test
    void countsTheSamplesThatPlay() {
        assertThat(packets(30, 0, 40, 0).nominalSampleCount()).isEqualTo(70 + 60);
    }
}
