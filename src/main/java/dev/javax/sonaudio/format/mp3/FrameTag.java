package dev.javax.sonaudio.format.mp3;

/**
 * What an ID3 frame holds: one of Sona's tags, or a tag Sona keeps under its own name.
 */
sealed interface FrameTag permits FrameTag.Known, FrameTag.Unknown {

    record Known(Id3Tag tag) implements FrameTag {}

    /**
     * @param name the tag's name, in lower case, as a FLAC file's Vorbis comment would name it
     */
    record Unknown(String name) implements FrameTag {}
}
