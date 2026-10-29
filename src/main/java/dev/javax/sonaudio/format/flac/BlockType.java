package dev.javax.sonaudio.format.flac;

// https://www.rfc-editor.org/rfc/rfc9639.html#name-metadata-block-header
public enum BlockType {
    STREAM_INFO,
    PADDING,
    APPLICATION,
    SEEK_TABLE,
    VORBIS_COMMENT,
    CUESHEET,
    PICTURE,
}
