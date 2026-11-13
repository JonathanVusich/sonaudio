package dev.javax.sonaudio.format.alac;

import dev.javax.sonaudio.metadata.Tag;
import dev.javax.sonaudio.metadata.TagValue;

/**
 * One value of a tag in a file's {@code ilst} box: one of sonaudio's tags, or a tag sonaudio keeps under its own name.
 */
sealed interface MetadataItem permits MetadataItem.Known, MetadataItem.Unknown {

    /**
     * @param tag the tag
     * @param value its value
     */
    record Known(Tag tag, TagValue value) implements MetadataItem {}

    /**
     * @param name the tag's name, in lower case, as a FLAC file's Vorbis comment would name it
     * @param value its value
     * @param trackCount how many tracks the movie had read when this was, which FFmpeg's gapless information is
     *     attached to the last of
     */
    record Unknown(String name, String value, int trackCount) implements MetadataItem {}
}
