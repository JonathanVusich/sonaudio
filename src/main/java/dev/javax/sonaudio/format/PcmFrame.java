package dev.javax.sonaudio.format;

/**
 * A block of decoded audio: the same number of samples for each channel, either signed integers ({@link IntPcmFrame}) or
 * floating-point numbers a decoder made before rounding them ({@link FloatPcmFrame}). A frame never changes once it's
 * made, so it can be kept after the decoder moves on to the next.
 */
public sealed interface PcmFrame permits IntPcmFrame, FloatPcmFrame {

    /**
     * @return samples per second, per channel, which a stream may change between frames
     */
    int sampleRate();

    int channelCount();

    /**
     * @return how many samples each channel has
     */
    int sampleCount();
}
