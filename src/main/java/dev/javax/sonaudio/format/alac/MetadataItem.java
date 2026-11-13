package dev.javax.sonaudio.format.alac;

import dev.javax.sonaudio.metadata.Tag;
import dev.javax.sonaudio.metadata.TagValue;

/**
 * One value of a tag in a file's {@code ilst} box: one of sonaudio's tags, or a tag sonaudio keeps under its own name.
 */
sealed interface MetadataItem permits MetadataItem.Known, MetadataItem.Unknown, MetadataItem.GaplessTag {

    /**
     * @param tag the tag
     * @param value its value
     */
    record Known(Tag tag, TagValue value) implements MetadataItem {}

    /**
     * @param name the tag's name, in lower case, as a FLAC file's Vorbis comment would name it
     * @param value its value
     * @param trackCount how many tracks the movie had read when this was, which is 0 for a tag that isn't freeform
     */
    record Unknown(String name, String value, int trackCount) implements MetadataItem {}

    /**
     * iTunes' gapless information, a freeform item named exactly {@code iTunSMPB}, which is also an unknown tag.
     *
     * @param value its text
     * @param trackCount how many tracks the movie had read when this was
     */
    record GaplessTag(String value, int trackCount) implements MetadataItem {}
}
