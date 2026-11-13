package dev.javax.sonaudio.format.opus;

/**
 * Whether a packet's frames are coded as one channel or two, from the TOC byte's stereo flag. It is how the stream is
 * coded, which a decoder may output as the other.
 */
enum ChannelCoding {
    MONO(1),
    STEREO(2);

    private final int channelCount;

    ChannelCoding(final int channelCount) {
        this.channelCount = channelCount;
    }

    /**
     * @return 1 or 2
     */
    int channelCount() {
        return channelCount;
    }
}
