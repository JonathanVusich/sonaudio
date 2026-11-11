package dev.javax.sonaudio.metadata;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The tags and duration a parser read from a file, which never change once they're made. A {@link RawMetadataBuilder}
 * collects them as a parser reads them.
 */
public final class RawMetadata {

    private final Duration duration;
    private final Map<Tag, Set<TagValue>> definedTags;
    private final Map<String, Set<TagValue>> unknownTags;

    /**
     * Makes metadata with no tags.
     *
     * @param duration how long the file's header says its audio plays
     */
    public RawMetadata(final Duration duration) {
        this(duration, Map.of(), Map.of());
    }

    /**
     * Makes metadata of the tags given, which it keeps rather than copies.
     *
     * @param duration how long the file's header says its audio plays
     * @param definedTags each known tag's values, in the order they were read, which no one else changes
     * @param unknownTags each other tag's values, in the order they were read, which no one else changes
     */
    public RawMetadata(
            final Duration duration,
            final Map<Tag, Set<TagValue>> definedTags,
            final Map<String, Set<TagValue>> unknownTags) {
        this.duration = duration;
        this.definedTags = definedTags;
        this.unknownTags = unknownTags;
    }

    /**
     * The duration the file's own header gives, which a bad encoder or an edit can leave wrong:
     * {@link dev.javax.sonaudio.format.AudioUtils#measureDuration} measures the audio itself.
     *
     * @return how long the file's header says its audio plays
     */
    public Duration duration() {
        return duration;
    }

    /**
     * Looks up a known tag.
     *
     * @param tag the tag to look up
     * @return its values in the order they were read, or none
     */
    public Set<TagValue> findValues(final Tag tag) {
        return definedTags.getOrDefault(tag, Set.of());
    }

    /**
     * Lists the tags that aren't {@link Tag}s, by the names the file gives them, lowercase.
     *
     * @return each unknown tag with its values
     */
    public Stream<TagSet> unknownTags() {
        return unknownTags.entrySet().stream().map(entry -> new TagSet(entry.getKey(), entry.getValue()));
    }
}
