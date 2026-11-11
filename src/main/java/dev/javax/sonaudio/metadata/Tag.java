package dev.javax.sonaudio.metadata;

/**
 * The tags every format maps its own fields to, so they're read the same way whatever the file's format.
 */
public enum Tag {
    /**
     * The release's artist: Vorbis comments' {@code ALBUMARTIST}, ID3's {@code TPE2}.
     */
    ALBUM_ARTIST,
    /**
     * The track's title: {@code TITLE}, {@code TIT2}.
     */
    TRACK_TITLE,
    /**
     * The track's artist: {@code ARTIST}, {@code TPE1}.
     */
    TRACK_ARTIST,
    /**
     * The release's title: {@code ALBUM}, {@code TALB}.
     */
    RELEASE_TITLE,
    /**
     * The year the release first came out: {@code ORIGINALYEAR}, {@code TORY} or {@code TDOR}.
     */
    RELEASE_YEAR,
    /**
     * The track's number on its medium: {@code TRACKNUMBER}, {@code TRCK}.
     */
    TRACK_NUMBER,
    /**
     * The number of the medium the track is on: {@code DISCNUMBER}, {@code TPOS}.
     */
    DISC_NUMBER,
    /**
     * The track's composer: {@code COMPOSER}, {@code TCOM}.
     */
    COMPOSER,
}
