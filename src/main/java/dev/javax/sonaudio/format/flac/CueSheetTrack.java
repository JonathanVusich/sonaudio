package dev.javax.sonaudio.format.flac;

import java.util.List;

// https://www.rfc-editor.org/rfc/rfc9639.html#name-cuesheet-track
public record CueSheetTrack(
        long offset,
        int number,
        String isrc,
        TrackType trackType,
        PreEmphasis preEmphasis,
        List<CueSheetIndexPoint> indexPoints) {}
