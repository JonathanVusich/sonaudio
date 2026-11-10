package dev.javax.sonaudio.format.flac;

import dev.javax.sonaudio.format.AudioUtils;
import dev.javax.sonaudio.format.MD5Checksum;
import java.time.Duration;

/**
 * A FLAC stream's STREAMINFO block, which every stream starts with (RFC 9639, section 8.2).
 *
 * @param header the block's header
 * @param checksum the MD5 digest of the unencoded audio
 * @param minBlockSize the fewest samples per channel of any frame but the last
 * @param maxBlockSize the most samples per channel of any frame
 * @param minFrameSize the fewest bytes of any frame, or 0 when the encoder didn't know
 * @param maxFrameSize the most bytes of any frame, or 0 when the encoder didn't know
 * @param sampleRate samples per second, per channel
 * @param numberOfChannels how many channels the audio has, 1 to 8
 * @param bitsPerSample how many bits each sample has, 4 to 32
 * @param interChannelSamples samples per channel in the whole stream, or 0 when the encoder didn't know
 */
public record StreamInfo(
        BlockHeader header,
        MD5Checksum checksum,

        int minBlockSize,
        int maxBlockSize,
        int minFrameSize,
        int maxFrameSize,
        int sampleRate,
        int numberOfChannels,
        int bitsPerSample,
        long interChannelSamples)
        implements Block {

    /**
     * The duration the block gives, which {@link AudioUtils#measureDuration} checks against the frames themselves.
     *
     * @return how long the audio plays
     */
    public Duration duration() {
        return AudioUtils.duration(interChannelSamples, sampleRate);
    }
}
