package dev.javax.sonaudio.metadata;

import java.util.Set;

/**
 * A tag that isn't a {@link Tag}, with its values.
 *
 * @param tag the tag's name, as the file gives it, lowercase
 * @param values its values, in the order they were read
 */
public record TagSet(String tag, Set<TagValue> values) {}
