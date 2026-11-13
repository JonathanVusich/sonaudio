package dev.javax.sonaudio.format.opus;

import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.FloatPcmAudio;
import dev.javax.sonaudio.format.FloatPcmFrame;
import java.io.IOException;

/**
 * The audio of an Opus file, which {@link OpusParser#parse} opens in its {@link ParsedOpus}, decoded to floating point
 * a frame at a time as each is asked for. Opus always decodes at 48000 Hz, whatever rate the file was recorded at.
 */
public final class OpusDecoder implements FloatPcmAudio {

    private static final int SAMPLE_RATE = 48000;
    private static final int BITS_PER_SAMPLE = 16;

    OpusDecoder() {}

    @Override
    public int sampleRate() {
        return SAMPLE_RATE;
    }

    @Override
    public int bitsPerSample() {
        return BITS_PER_SAMPLE;
    }

    @Override
    public boolean hasNextFrame() throws IOException, InvalidFormatException {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public FloatPcmFrame nextFrame() throws IOException, InvalidFormatException {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
