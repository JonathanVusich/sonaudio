package dev.javax.sonaudio.format.alac;

/**
 * The ALACSpecificConfig of an ALAC stream, which the decoder is set up by: the 24 bytes after the version of the
 * {@code alac} box in the file's sample entry.
 *
 * @param frameLength the most samples per channel in a packet; the last packet may have fewer
 * @param bitsPerSample how many bits each sample has: 16, 20, 24 or 32
 * @param riceHistoryMultiplier the history multiplier ({@code pb}) of the adaptive Golomb-Rice coder
 * @param riceInitialHistory the history the coder starts each channel of a packet with ({@code mb})
 * @param riceLimit the largest Rice parameter the coder uses ({@code kb})
 * @param channelCount how many channels the audio has, 1 to 8
 * @param maxRun the longest run the encoder codes, which the decoder doesn't need
 * @param maxFrameBytes the size of the largest packet, or 0 when the encoder didn't know
 * @param averageBitrate bits per second, or 0 when the encoder didn't know
 * @param sampleRate samples per second, per channel
 */
public record AlacCookie(
        int frameLength,
        int bitsPerSample,
        int riceHistoryMultiplier,
        int riceInitialHistory,
        int riceLimit,
        int channelCount,
        int maxRun,
        long maxFrameBytes,
        long averageBitrate,
        int sampleRate) {}
