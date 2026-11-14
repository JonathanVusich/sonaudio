package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.javax.sonaudio.exception.InvalidFormatException;
import org.junit.jupiter.api.Test;

/**
 * Checks how many samples a stream gives for each packet, which is what FFmpeg's decoder gives while SILK's resampler
 * holds samples back: the first SILK frame of narrowband is 24 short, and they come out when the stream leaves SILK, or
 * ends.
 */
class OpusStreamTest {

    // The TOC configurations of 20 ms frames: narrowband SILK, wideband SILK, super-wideband hybrid and fullband CELT.
    private static final int SILK_NARROWBAND = 1;
    private static final int SILK_WIDEBAND = 9;
    private static final int SILK_NARROWBAND_10_MS = 0;
    private static final int HYBRID_SUPERWIDEBAND = 13;
    private static final int CELT_FULLBAND = 31;

    private final OpusParser parser = new OpusParser();

    @Test
    void holdsBackTheFirstNarrowbandFrameAndGivesItAtTheEnd() throws InvalidFormatException {
        final var stream = new OpusStream(1);

        assertThat(decode(stream, SILK_NARROWBAND)).isEqualTo(960 - 24);
        assertThat(decode(stream, SILK_NARROWBAND)).isEqualTo(960);
        assertThat(stream.drain()).isEqualTo(24);
        assertThat(stream.drain()).isZero();
    }

    @Test
    void holdsNothingBackInWidebandOrAnyOtherBandwidth() throws InvalidFormatException {
        final var stream = new OpusStream(1);

        assertThat(decode(stream, SILK_WIDEBAND)).isEqualTo(960);
        assertThat(decode(stream, SILK_WIDEBAND)).isEqualTo(960);
        assertThat(stream.drain()).isZero();
    }

    @Test
    void givesTheHeldBackSamplesBeforeACeltFrame() throws InvalidFormatException {
        final var stream = new OpusStream(1);
        decode(stream, SILK_NARROWBAND);

        assertThat(decode(stream, CELT_FULLBAND)).isEqualTo(24 + 960);
        assertThat(stream.drain()).isZero();
    }

    @Test
    void givesTheHeldBackSamplesWhenSilkChangesItsRate() throws InvalidFormatException {
        final var stream = new OpusStream(1);
        decode(stream, SILK_NARROWBAND);

        assertThat(decode(stream, SILK_WIDEBAND)).isEqualTo(24 + 960);
        assertThat(decode(stream, SILK_NARROWBAND)).isEqualTo(960 - 24);
    }

    @Test
    void holdsBackAgainWhenSilkStartsAgainAfterCelt() throws InvalidFormatException {
        final var stream = new OpusStream(1);
        decode(stream, SILK_NARROWBAND);
        decode(stream, CELT_FULLBAND);

        assertThat(decode(stream, SILK_NARROWBAND)).isEqualTo(960 - 24);
        assertThat(stream.drain()).isEqualTo(24);
    }

    @Test
    void decodesHybridFramesWithoutAnyDelay() throws InvalidFormatException {
        final var stream = new OpusStream(2);

        assertThat(decode(stream, HYBRID_SUPERWIDEBAND)).isEqualTo(960);
        assertThat(decode(stream, HYBRID_SUPERWIDEBAND)).isEqualTo(960);
        assertThat(stream.drain()).isZero();
    }

    @Test
    void decodesEveryFrameOfAPacket() throws InvalidFormatException {
        final var stream = new OpusStream(1);
        // Two frames of 10 ms of narrowband SILK, in a packet of code 1.
        final var twoFrames = new byte[1 + 20];
        twoFrames[0] = (byte) (SILK_NARROWBAND_10_MS << 3 | 1);
        final var packet = parser.parsePacket(twoFrames, 0, twoFrames.length, Delimiting.UNDELIMITED);

        assertThat(stream.decodePacket(twoFrames, 0, packet)).isEqualTo(2 * 480 - 24);
    }

    @Test
    void decodesFramesOfEveryBytesSizeWithoutFailing() throws InvalidFormatException {
        for (var size = 0; size < 40; size++) {
            for (final var config : new int[] {SILK_NARROWBAND, SILK_WIDEBAND, HYBRID_SUPERWIDEBAND, CELT_FULLBAND}) {
                final var stream = new OpusStream(2);
                final var bytes = new byte[1 + size];
                bytes[0] = (byte) (config << 3 | 4);
                final var packet = parser.parsePacket(bytes, 0, bytes.length, Delimiting.UNDELIMITED);

                final var samples = stream.decodePacket(bytes, 0, packet);

                assertThat(samples).as("config %d, %d bytes", config, size).isBetween(960 - 24, 960);
            }
        }
    }

    @Test
    void outputsAsManyChannelsAsTheStreamHas() {
        assertThat(new OpusStream(1).output()).hasNumberOfRows(1);
        assertThat(new OpusStream(2).output()).hasNumberOfRows(2);
    }

    private int decode(final OpusStream stream, final int config) throws InvalidFormatException {
        final var bytes = new byte[1 + 20];
        bytes[0] = (byte) (config << 3);
        final var packet = parser.parsePacket(bytes, 0, bytes.length, Delimiting.UNDELIMITED);
        return stream.decodePacket(bytes, 0, packet);
    }
}
