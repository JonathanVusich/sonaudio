package dev.javax.sonaudio.format.opus;

/**
 * Where one output channel of the file gets its samples from, as the channel mapping of its {@code OpusHead} gives
 * (RFC 7845 section 5.1.1.2).
 */
sealed interface ChannelSource {

    /**
     * A channel that the mapping gives to silence.
     */
    record Silent() implements ChannelSource {}

    /**
     * A channel that the mapping gives the same coded channel as an earlier one.
     *
     * @param outputChannel the earlier channel, which it copies
     */
    record Copied(int outputChannel) implements ChannelSource {}

    /**
     * A channel of a coded stream.
     *
     * @param stream the stream, from 0
     * @param streamChannel the channel in the stream: 0, or 1 for the right of a stereo stream
     */
    record Coded(int stream, int streamChannel) implements ChannelSource {}
}
