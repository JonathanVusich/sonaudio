package dev.javax.sonaudio.format.mp3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.junit.jupiter.api.Test;
import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.samples.Mp3Sample;

class Mp3StreamInfoTest {

    // The ID3v2 tag before the first frame of each sample, and the ID3v1 tag after the last.
    private static final int STEREO_CBR_TAG_SIZE = 291;
    private static final int MONO_VBR_TAG_SIZE = 133;
    private static final int ID3V1_SIZE = 128;

    // The LAME tag gives the encoder's delay and padding, so the duration is exactly the one second encoded. The Info
    // header's frame is silence, not audio, so the audio starts after it.
    @Test
    void takesLengthFromInfoHeaderAndLameTag() throws IOException, InvalidFormatException {
        final var bytes = sampleBytes(Mp3Sample.STEREO_CBR_ID3V24);
        final var streamInfo = readStreamInfo(bytes, STEREO_CBR_TAG_SIZE, bytes.length);

        assertThat(streamInfo.audioStart()).isEqualTo(STEREO_CBR_TAG_SIZE + 417);
        assertThat(streamInfo.duration()).isEqualTo(Duration.ofSeconds(1));
        assertThat(streamInfo.sampleCount()).isEqualTo(44100);
        assertThat(streamInfo.channelCount()).isEqualTo(2);
        assertThat(streamInfo.sampleRate()).isEqualTo(44100);
    }

    @Test
    void readsMpeg2VbrStream() throws IOException, InvalidFormatException {
        final var bytes = sampleBytes(Mp3Sample.MONO_VBR_ID3V23);
        final var streamInfo = readStreamInfo(bytes, MONO_VBR_TAG_SIZE, bytes.length - ID3V1_SIZE);

        // The Xing header's frame takes 182 bytes. Its byte count is more than a sixteenth short of the file after its
        // header, as the file ends with an ID3v1 tag, so FFmpeg takes it for two files joined and trims no padding
        // from its end, only the encoder's delay from its start.
        assertThat(streamInfo)
                .isEqualTo(new Mp3StreamInfo(
                        MONO_VBR_TAG_SIZE + 182,
                        bytes.length - ID3V1_SIZE,
                        22050,
                        1,
                        576,
                        41,
                        new EncoderTrim.Lame(576, 0),
                        0));
        assertThat(streamInfo.sampleCount()).isEqualTo(41 * 576 - 576 - 529);
    }

    // Without a VBR header the frames are counted, and the encoder's delay and padding are unknown, so they're counted
    // as audio.
    @Test
    void countsFramesWithoutVbrHeader() throws IOException, InvalidFormatException {
        final var bytes = sampleBytes(Mp3Sample.NO_XING_ID3V1);
        final var streamInfo = readStreamInfo(bytes, 0, bytes.length - ID3V1_SIZE);

        assertThat(streamInfo.audioStart()).isZero();
        assertThat(streamInfo.frameCount()).isEqualTo(40);
        assertThat(streamInfo.duration()).isEqualTo(Duration.ofNanos(40 * 1152 * 1_000_000_000L / 44100));
    }

    // Each free-format frame runs to the next one's sync: 417 bytes, and 418 when padded, as at 128 kbit/s.
    @Test
    void countsFreeFormatFrames() throws IOException, InvalidFormatException {
        final var bytes = sampleBytes(Mp3Sample.FREE_FORMAT);
        final var streamInfo = readStreamInfo(bytes, 0, bytes.length - ID3V1_SIZE);

        assertThat(streamInfo.frameCount()).isEqualTo(40);
        assertThat(streamInfo.duration()).isEqualTo(Duration.ofNanos(40 * 1152 * 1_000_000_000L / 44100));
    }

    // The first frame takes 417 bytes, so the second starts there. Without its header, the rest of it is bytes before
    // the third frame's, which FFmpeg drops too.
    @Test
    void leavesOutFramesFfmpegDrops() throws IOException, InvalidFormatException {
        final var bytes = sampleBytes(Mp3Sample.NO_XING_ID3V1);
        Arrays.fill(bytes, 417, 417 + FrameHeader.SIZE, (byte) 0);

        final var streamInfo = readStreamInfo(bytes, 0, bytes.length - ID3V1_SIZE);

        assertThat(streamInfo.frameCount()).isEqualTo(38);
    }

    // FFmpeg's demuxer skips bytes before the first frame that's followed by another.
    @Test
    void findsFirstFrameAfterOtherBytes() throws IOException, InvalidFormatException {
        final var frames = sampleBytes(Mp3Sample.NO_XING_ID3V1);
        final var bytes = new byte[100 + frames.length];
        Arrays.fill(bytes, 0, 100, (byte) 0x55);
        System.arraycopy(frames, 0, bytes, 100, frames.length);

        final var streamInfo = readStreamInfo(bytes, 0, bytes.length - ID3V1_SIZE);

        assertThat(streamInfo.audioStart()).isEqualTo(100);
        assertThat(streamInfo.frameCount()).isEqualTo(40);
    }

    // FFmpeg decodes a last frame the file cuts short, as if the rest of it were the bytes after it, then zeros.
    @Test
    void countsFrameCutShort() throws IOException, InvalidFormatException {
        final var bytes = sampleBytes(Mp3Sample.NO_XING_ID3V1);

        final var streamInfo = readStreamInfo(bytes, 0, bytes.length - ID3V1_SIZE - 200);

        assertThat(streamInfo.frameCount()).isEqualTo(40);
    }

    @Test
    void rejectsStreamWithoutFrames() {
        final var bytes = new byte[64];

        assertThatThrownBy(() -> readStreamInfo(bytes, 0, bytes.length))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessage("MP3 has no frames of audio!");
    }

    private static Mp3StreamInfo readStreamInfo(final byte[] bytes, final int audioStart, final int audioEnd)
            throws IOException, InvalidFormatException {
        return new Mp3Parser().readStreamInfo(new SeekableInMemoryByteChannel(bytes), audioStart, audioEnd);
    }

    private static byte[] sampleBytes(final Mp3Sample sample) throws IOException {
        try (final var inputStream = sample.inputStream()) {
            return inputStream.readAllBytes();
        }
    }
}
