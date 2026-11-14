package dev.javax.sonaudio.format.opus;

/**
 * How an Opus file's channels map to its coded streams, from the channel mapping family of its {@code OpusHead} (RFC
 * 7845 section 5.1.1).
 */
public enum ChannelMappingFamily {
    /**
     * Family 0: one stream of mono or stereo, in that order.
     */
    SINGLE_STREAM,
    /**
     * Family 1: up to eight channels in the order of Vorbis, which FFmpeg decodes to its own order for them.
     */
    VORBIS_ORDER,
    /**
     * Family 2: ambisonics, in the ACN channel order.
     */
    AMBISONICS,
    /**
     * Family 255: channels with no defined meaning.
     */
    UNDEFINED
}
