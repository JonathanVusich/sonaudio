package dev.javax.sonaudio.format.flac;

// https://www.rfc-editor.org/rfc/rfc9639.html#name-cuesheet-track-index-point
public record CueSheetIndexPoint(long offset, int number) {}
