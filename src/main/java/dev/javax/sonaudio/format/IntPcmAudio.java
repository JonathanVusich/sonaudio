package dev.javax.sonaudio.format;

import java.io.IOException;
import java.util.NoSuchElementException;
import dev.javax.sonaudio.exception.InvalidFormatException;

/**
 * Decoded audio whose frames are integers, as a lossless decoder such as FLAC's makes them.
 */
public interface IntPcmAudio extends PcmAudio {

    /**
     * @return the next frame
     * @throws NoSuchElementException when there are no more frames
     * @throws IOException when the stream can't be read, or ends before a frame does
     * @throws InvalidFormatException when the frame is corrupt
     */
    IntPcmFrame nextFrame() throws IOException, InvalidFormatException;
}
