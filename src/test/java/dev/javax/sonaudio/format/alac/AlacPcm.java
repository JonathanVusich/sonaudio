package dev.javax.sonaudio.format.alac;

import static org.assertj.core.api.Assertions.assertThat;

import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.IntPcmAudio;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import lombok.experimental.UtilityClass;

/**
 * Decodes audio to the raw little-endian samples that FFmpeg's references are, and compares them.
 */
@UtilityClass
final class AlacPcm {

    /**
     * @return every frame's samples, channels interleaved, each in as many bytes as the audio's bit depth fills
     */
    static byte[] decodeToBytes(final IntPcmAudio audio) throws IOException, InvalidFormatException {
        final var bytesPerSample = audio.bitsPerSample() / Byte.SIZE;
        final var output = new ByteArrayOutputStream();
        while (audio.hasNextFrame()) {
            final var frame = audio.nextFrame();
            for (var index = 0; index < frame.sampleCount(); index++) {
                for (var channel = 0; channel < frame.channelCount(); channel++) {
                    final var sample = frame.channelSample(channel, index);
                    for (var byteIndex = 0; byteIndex < bytesPerSample; byteIndex++) {
                        output.write(sample >> (Byte.SIZE * byteIndex));
                    }
                }
            }
        }
        return output.toByteArray();
    }

    /**
     * Checks the bytes against the reference, and fails with where they first differ, as a sample of a channel.
     */
    static void assertSamePcm(
            final byte[] expected, final byte[] actual, final int channels, final int bytesPerSample) {
        final var frameBytes = channels * bytesPerSample;
        final var common = Math.min(expected.length, actual.length);
        for (var position = 0; position < common; position++) {
            if (expected[position] != actual[position]) {
                final var sample = position / frameBytes;
                final var channel = position % frameBytes / bytesPerSample;
                assertThat(actual[position])
                        .as("first difference at sample %d of channel %d (byte %d)", sample, channel, position)
                        .isEqualTo(expected[position]);
            }
        }
        assertThat(actual.length).as("decoded length in bytes").isEqualTo(expected.length);
    }
}
