package dev.javax.sonaudio.samples;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

/**
 * Short synthetic FLAC files with their audio: a chord, silence and noise, encoded with settings that between them use
 * every subframe type, channel assignment and residual coding method. The reference encoder (flac 1.5) encoded all
 * but {@link #MONO_16_BIT_VERBATIM}, which FFmpeg's encoder did, as only it stores white noise verbatim.
 * {@link #SILENCE_10_SECONDS} is the audio {@link FlacFiles} gives its files, which FFmpeg also encoded.
 */
public enum FlacAudioSample {
    STEREO_16_BIT_FIXED("stereo_16bit_fixed.flac"),
    STEREO_16_BIT_LPC("stereo_16bit_lpc.flac"),
    STEREO_LPC_ORDER_32("stereo_lpc_order_32.flac"),
    STEREO_24_BIT_96_KHZ("stereo_24bit_96khz.flac"),
    STEREO_32_BIT("stereo_32bit.flac"),
    WASTED_BITS("wasted_bits.flac"),
    MONO_8_BIT_NOISE("mono_8bit_noise.flac"),
    MONO_16_BIT_VERBATIM("mono_16bit_verbatim.flac"),
    THREE_CHANNELS_ODD_BLOCK_SIZE("three_channels_odd_blocksize.flac"),
    SILENCE_10_SECONDS("silence_10s.flac");

    private final String fileName;

    FlacAudioSample(final String fileName) {
        this.fileName = fileName;
    }

    public InputStream inputStream() {
        return FlacAudioSample.class.getResourceAsStream("audio/" + fileName);
    }

    /**
     * @return the file in memory, as parsers read it
     */
    public SeekableByteChannel channel() throws IOException {
        try (final var inputStream = inputStream()) {
            return new SeekableInMemoryByteChannel(inputStream.readAllBytes());
        }
    }
}
