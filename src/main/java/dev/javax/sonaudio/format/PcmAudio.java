package dev.javax.sonaudio.format;

import java.io.IOException;
import dev.javax.sonaudio.exception.InvalidFormatException;

/**
 * Decoded audio, read a frame at a time, all of one kind: {@link IntPcmAudio} gives integers and {@link FloatPcmAudio}
 * floating-point numbers. It reads its stream once, so each frame can only be read once.
 */
public interface PcmAudio {

    /**
     * @return samples per second, per channel
     */
    int sampleRate();

    /**
     * @return how many bits each sample has, so how large its values may be
     */
    int bitsPerSample();

    /**
     * @return whether there's another frame to read
     * @throws IOException when the stream can't be read
     * @throws InvalidFormatException when the audio is corrupt
     */
    boolean hasNextFrame() throws IOException, InvalidFormatException;
}
