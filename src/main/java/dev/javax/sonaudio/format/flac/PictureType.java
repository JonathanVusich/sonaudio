package dev.javax.sonaudio.format.flac;

// https://www.rfc-editor.org/rfc/rfc9639.html#name-picture
// Declared in the order of the RFC's picture type table: each constant's ordinal is its value.
public enum PictureType {
    OTHER,
    FILE_ICON_32X32,
    FILE_ICON,
    FRONT_COVER,
    BACK_COVER,
    LINER_NOTES,
    MEDIA_LABEL,
    LEAD_ARTIST,
    ARTIST,
    CONDUCTOR,
    BAND,
    COMPOSER,
    LYRICIST,
    RECORDING_LOCATION,
    DURING_RECORDING,
    DURING_PERFORMANCE,
    SCREEN_CAPTURE,
    BRIGHT_COLORED_FISH,
    ILLUSTRATION,
    ARTIST_LOGOTYPE,
    PUBLISHER_LOGOTYPE,
}
