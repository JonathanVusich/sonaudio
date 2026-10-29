package dev.javax.sonaudio.format.flac;

public sealed interface Block permits Application, CueSheet, Padding, Picture, SeekTable, StreamInfo, VorbisComment {

    BlockHeader header();
}
