package dev.javax.sonaudio.format;

import java.io.IOException;
import java.util.NoSuchElementException;
import dev.javax.sonaudio.exception.InvalidFormatException;

/**
 * Decoded audio whose frames are floating-point numbers, as a decoder that works in floating point makes them.
 */
public interface FloatPcmAudio extends PcmAudio {

    /**
     * @return the next frame
     * @throws NoSuchElementException when there are no more frames
     * @throws IOException when the stream can't be read, or ends before a frame does
     * @throws InvalidFormatException when the frame is corrupt
     */
    FloatPcmFrame nextFrame() throws IOException, InvalidFormatException;
}
