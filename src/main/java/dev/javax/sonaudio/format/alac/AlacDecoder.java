package dev.javax.sonaudio.format.alac;

import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.IntPcmAudio;
import dev.javax.sonaudio.format.IntPcmFrame;
import java.io.IOException;

/**
 * The audio of an ALAC file, which {@link AlacParser#parse} opens in its {@link ParsedAlac}, decoded to integers a
 * frame at a time as each is asked for.
 */
public final class AlacDecoder implements IntPcmAudio {

    AlacDecoder() {}

    @Override
    public int sampleRate() {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public int bitsPerSample() {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public boolean hasNextFrame() throws IOException, InvalidFormatException {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public IntPcmFrame nextFrame() throws IOException, InvalidFormatException {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
