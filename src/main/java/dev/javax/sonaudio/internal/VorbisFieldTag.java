package dev.javax.sonaudio.internal;

/**
 * What a Vorbis comment field holds: one of sonaudio's tags, or a tag sonaudio keeps under its own name.
 */
public sealed interface VorbisFieldTag permits VorbisFieldTag.Known, VorbisFieldTag.Unknown {

    /**
     * @param tag the tag the field is
     */
    record Known(VorbisTag tag) implements VorbisFieldTag {}

    /**
     * @param name the field's name, in lower case
     */
    record Unknown(String name) implements VorbisFieldTag {}
}
