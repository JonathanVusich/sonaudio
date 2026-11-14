package dev.javax.sonaudio.format;

import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.alac.AlacDecoder;
import dev.javax.sonaudio.format.flac.FlacDecoder;
import java.io.IOException;
import java.util.NoSuchElementException;

/**
 * Decoded audio whose frames are integers, as a lossless decoder such as FLAC's makes them.
 */
public sealed interface IntPcmAudio extends PcmAudio permits AlacDecoder, FlacDecoder {

    /**
     * @return the next frame
     * @throws NoSuchElementException when there are no more frames
     * @throws IOException when the stream can't be read, or ends before a frame does
     * @throws InvalidFormatException when the frame is corrupt
     */
    IntPcmFrame nextFrame() throws IOException, InvalidFormatException;
}
