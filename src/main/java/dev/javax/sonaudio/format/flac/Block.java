package dev.javax.sonaudio.format.flac;

/**
 * A FLAC metadata block (RFC 9639, section 8).
 */
public sealed interface Block permits Application, CueSheet, Padding, Picture, SeekTable, StreamInfo, VorbisComment {

    /**
     * @return the block's header
     */
    BlockHeader header();
}
