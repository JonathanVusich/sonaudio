package dev.javax.sonaudio.format.flac;

// https://www.rfc-editor.org/rfc/rfc9639.html#name-metadata-block-header
/**
 * The kinds of FLAC metadata block, in the order of their codes.
 */
public enum BlockType {
    /**
     * The stream's sample rate, channels, bit depth, length and checksum.
     */
    STREAM_INFO,
    /**
     * Space left for metadata to grow into.
     */
    PADDING,
    /**
     * Data of an application's own.
     */
    APPLICATION,
    /**
     * Points to seek to in the audio.
     */
    SEEK_TABLE,
    /**
     * The tags.
     */
    VORBIS_COMMENT,
    /**
     * The track and index points of a CD.
     */
    CUESHEET,
    /**
     * A picture, such as the front cover.
     */
    PICTURE,
}
