package dev.javax.sonaudio.format.flac;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;
import dev.javax.sonaudio.metadata.TagValue;

@UtilityClass
public final class FlacUtils {

    /**
     * @return one Vorbis comment field per value, under the field name in upper case
     */
    public static Stream<Field> toFields(final String fieldName, final Set<TagValue> values) {
        final var name = fieldName.toUpperCase(Locale.ROOT);
        return values.stream().map(value -> new Field(name, value.toText()));
    }
}
