package dev.javax.sonaudio.format;

/**
 * Decoded audio, read a frame at a time as it's iterated, all of one kind: {@link IntPcmAudio} gives integers and
 * {@link FloatPcmAudio} floating-point numbers.
 */
public sealed interface PcmAudio permits IntPcmAudio, FloatPcmAudio {

    /**
     * @return samples per second, per channel
     */
    int sampleRate();

    /**
     * @return how many bits each sample has, so how large its values may be
     */
    int bitsPerSample();
}
