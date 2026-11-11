package dev.javax.sonaudio.format;

import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.mp3.Mp3Decoder;
import java.io.IOException;
import java.util.NoSuchElementException;

/**
 * Decoded audio whose frames are floating-point numbers, as a decoder that works in floating point makes them.
 */
public sealed interface FloatPcmAudio extends PcmAudio permits Mp3Decoder {

    /**
     * @return the next frame
     * @throws NoSuchElementException when there are no more frames
     * @throws IOException when the stream can't be read, or ends before a frame does
     * @throws InvalidFormatException when the frame is corrupt
     */
    FloatPcmFrame nextFrame() throws IOException, InvalidFormatException;
}
