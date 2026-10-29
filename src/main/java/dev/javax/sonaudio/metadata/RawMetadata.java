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

    public RawMetadata(final Duration duration) {
        this(duration, Map.of(), Map.of());
    }

    /**
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

    public Duration duration() {
        return duration;
    }

    public Set<TagValue> findValues(final Tag tag) {
        return definedTags.getOrDefault(tag, Set.of());
    }

    public Stream<TagSet> unknownTags() {
        return unknownTags.entrySet().stream().map(entry -> new TagSet(entry.getKey(), entry.getValue()));
    }
}
