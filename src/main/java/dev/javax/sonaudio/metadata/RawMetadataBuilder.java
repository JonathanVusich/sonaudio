package dev.javax.sonaudio.metadata;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Collects the tags a parser reads, to make a {@link RawMetadata}. A tag keeps each value once, in the order its
 * values were first added.
 */
public final class RawMetadataBuilder {

    private final Duration duration;
    private final Map<Tag, Set<TagValue>> definedTags = new EnumMap<>(Tag.class);
    private final Map<String, Set<TagValue>> unknownTags = new HashMap<>();

    /**
     * Starts metadata with no tags.
     *
     * @param duration how long the file's header says its audio plays
     */
    public RawMetadataBuilder(final Duration duration) {
        this.duration = duration;
    }

    /**
     * Adds a value of a known tag.
     *
     * @param tag the tag
     * @param value one of its values
     */
    public void addTag(final Tag tag, final TagValue value) {
        definedTags.computeIfAbsent(tag, _ -> new LinkedHashSet<>()).add(value);
    }

    /**
     * Adds a value of a tag that isn't a {@link Tag}.
     *
     * @param tag the tag's name, as the file gives it, lowercase
     * @param value one of its values
     */
    public void addUnknownTag(final String tag, final String value) {
        unknownTags.computeIfAbsent(tag, _ -> new LinkedHashSet<>()).add(new TagValue.Str(value));
    }

    /**
     * Makes the metadata of the tags added so far, which later additions don't change.
     *
     * @return the metadata
     */
    public RawMetadata build() {
        final var definedCopy = new EnumMap<Tag, Set<TagValue>>(Tag.class);
        definedTags.forEach((tag, values) -> definedCopy.put(tag, unmodifiableValues(values)));
        final var unknownCopy = new LinkedHashMap<String, Set<TagValue>>();
        unknownTags.forEach((tag, values) -> unknownCopy.put(tag, unmodifiableValues(values)));
        return new RawMetadata(
                duration, Collections.unmodifiableMap(definedCopy), Collections.unmodifiableMap(unknownCopy));
    }

    private static Set<TagValue> unmodifiableValues(final Set<TagValue> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }
}
