package dev.javax.sonaudio.format.mp3;

import java.util.List;

/**
 * A frame of an ID3 tag that Sona reads. Frames of any other kind (pictures, comments, ...) are skipped.
 */
sealed interface Id3Frame permits Id3Frame.TextFrame, Id3Frame.UserTextFrame, Id3Frame.UniqueFileId {

    /**
     * A text information frame, whose ID starts with T (ID3v2.4.0 frames section 4.2).
     *
     * @param values more than one only where ID3v2.4 separates them with NULs
     */
    record TextFrame(String frameId, List<String> values) implements Id3Frame {}

    /**
     * A TXXX frame: text that the tagger names itself, as Picard does MusicBrainz IDs.
     */
    record UserTextFrame(String description, List<String> values) implements Id3Frame {}

    /**
     * A UFID frame: an ID the owner gave the file, as MusicBrainz does recordings.
     */
    record UniqueFileId(String owner, String identifier) implements Id3Frame {}
}
