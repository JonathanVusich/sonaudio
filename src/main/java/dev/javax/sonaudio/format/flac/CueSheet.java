package dev.javax.sonaudio.format.flac;

import java.util.List;

// https://www.rfc-editor.org/rfc/rfc9639.html#name-cuesheet
record CueSheet(
        BlockHeader header,
        String mediaCatalogNumber,
        long leadInSamples,
        CueSheetMedium medium,
        List<CueSheetTrack> tracks)
        implements Block {}
