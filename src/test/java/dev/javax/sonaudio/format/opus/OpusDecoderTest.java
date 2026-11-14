package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.FloatPcmFrame;
import dev.javax.sonaudio.format.FrameContinuity;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.junit.jupiter.api.Test;

class OpusDecoderTest {

    private static final int SERIAL = 3;
    private static final int PRE_SKIP = 312;
    private static final int SILK_NARROWBAND = 1;
    private static final int SILK_WIDEBAND = 9;

    @Test
    void dropsThePreSkipAndTheEndOfTheLastPacket() throws IOException, InvalidFormatException {
        // 3 packets of 960 samples, from which 312 are the pre-skip and, as the last granule position is 100 short of
        // their 2880, 100 are the end.
        final var file = OggTestFiles.opusFile(SERIAL, PRE_SKIP, 2880 - 100, wideband(), wideband(), wideband());

        final var frames = decode(file);

        assertThat(frames).extracting(FloatPcmFrame::sampleCount).containsExactly(960 - 312, 960, 960 - 100);
    }

    @Test
    void dropsAFirstPacketWhollyWhenThePreSkipIsLonger() throws IOException, InvalidFormatException {
        final var file = OggTestFiles.opusFile(SERIAL, 1000, 2880, wideband(), wideband(), wideband());

        final var frames = decode(file);

        assertThat(frames).extracting(FloatPcmFrame::sampleCount).containsExactly(920, 960);
    }

    @Test
    void givesThesamplesANarrowbandResamplerHeldBackAsAFrameAfterTheLastPacket()
            throws IOException, InvalidFormatException {
        final var file = OggTestFiles.opusFile(SERIAL, PRE_SKIP, 2880 - 100, narrowband(), narrowband(), narrowband());

        final var frames = decode(file);

        // The first packet is 24 short as well as 312 shorter, and the held back 24 are not trimmed.
        assertThat(frames).extracting(FloatPcmFrame::sampleCount).containsExactly(960 - 24 - 312, 960, 960 - 100, 24);
    }

    @Test
    void doesNotTrimWhenThePaddingIsMoreThanTheFrameHolds() throws IOException, InvalidFormatException {
        // The one packet is 960 samples, of which a granule position of 0 asks for all to be dropped, but its frame is
        // 24 short, and FFmpeg drops the end of a frame only if it holds that many samples.
        final var file = OggTestFiles.opusFile(SERIAL, 0, 0, narrowband());

        final var frames = decode(file);

        assertThat(frames).extracting(FloatPcmFrame::sampleCount).containsExactly(960 - 24, 24);
    }

    @Test
    void marksTheFramesAfterAPacketItCouldNotDecode() throws IOException, InvalidFormatException {
        // A packet of code 3 with no frames is the packet of 0 samples that FFmpeg's decoder returns an error for.
        final var undecodable = new byte[] {(byte) (SILK_WIDEBAND << 3 | 3), 0};
        final var file = OggTestFiles.opusFile(SERIAL, 0, 1920, wideband(), undecodable, wideband());

        final var frames = decode(file);

        assertThat(frames).extracting(FloatPcmFrame::sampleCount).containsExactly(960, 960);
        assertThat(frames)
                .extracting(FloatPcmFrame::continuity)
                .containsExactly(FrameContinuity.CONTINUOUS, FrameContinuity.AFTER_DECODING_ERROR);
    }

    @Test
    void givesFramesOfTheChannelsOfItsHeaderAt48Kilohertz() throws IOException, InvalidFormatException {
        final var file = OggTestFiles.opusFile(SERIAL, 0, 960, wideband());

        final var frame = decode(file).getFirst();

        assertThat(frame.channelCount()).isEqualTo(1);
        assertThat(frame.sampleRate()).isEqualTo(48000);
        assertThat(frame.channelSample(0, 0)).isZero();
    }

    @Test
    void failsToGiveAFrameThatThereIsNone() throws IOException, InvalidFormatException {
        final var file = OggTestFiles.opusFile(SERIAL, 0, 960, wideband());
        final var audio =
                new OpusParser().parse(new SeekableInMemoryByteChannel(file)).audio();
        audio.nextFrame();

        assertThat(audio.hasNextFrame()).isFalse();
        assertThatThrownBy(audio::nextFrame).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void asksForAFrameAsOftenAsItLikes() throws IOException, InvalidFormatException {
        final var file = OggTestFiles.opusFile(SERIAL, 0, 960, wideband());
        final var audio =
                new OpusParser().parse(new SeekableInMemoryByteChannel(file)).audio();

        assertThat(audio.hasNextFrame()).isTrue();
        assertThat(audio.hasNextFrame()).isTrue();
        assertThat(audio.nextFrame().sampleCount()).isEqualTo(960);
    }

    private byte[] wideband() {
        return OggTestFiles.audioPacket(SILK_WIDEBAND, 20);
    }

    private byte[] narrowband() {
        return OggTestFiles.audioPacket(SILK_NARROWBAND, 20);
    }

    private List<FloatPcmFrame> decode(final byte[] file) throws IOException, InvalidFormatException {
        final var audio =
                new OpusParser().parse(new SeekableInMemoryByteChannel(file)).audio();
        final var frames = new ArrayList<FloatPcmFrame>();
        while (audio.hasNextFrame()) {
            frames.add(audio.nextFrame());
        }
        return frames;
    }
}
