package dev.javax.sonaudio.metadata;

import java.util.Optional;

/**
 * One value of a tag, of the type the format stores it as, or that its tag is read as.
 */
public sealed interface TagValue permits TagValue.Str, TagValue.Int, TagValue.Binary {

    /**
     * A text value.
     *
     * @param value the text
     */
    record Str(String value) implements TagValue {}

    /**
     * A number, as a track or disc number or a year is read.
     *
     * @param value the number
     */
    record Int(int value) implements TagValue {}

    /**
     * Binary data.
     *
     * @param blob the data
     */
    record Binary(Blob blob) implements TagValue {}

    /**
     * Writes the value out as text.
     *
     * @return the value as text, as formats that store tags as text write it
     */
    default String toText() {
        return switch (this) {
            case Str(String value) -> value;
            case Int(int value) -> Integer.toString(value);
            case Binary _ -> throw new IllegalArgumentException("Binary tags can't be written as text");
        };
    }

    /**
     * Reads the value as text, if it is text.
     *
     * @return the value if it is a string, or empty
     */
    default Optional<String> asString() {
        return switch (this) {
            case Str(String value) -> Optional.of(value);
            case Int _, Binary _ -> Optional.empty();
        };
    }

    /**
     * Reads the value as a number, if it is a number.
     *
     * @return the value if it is a number, or empty
     */
    default Optional<Integer> asInt() {
        return switch (this) {
            case Int(int value) -> Optional.of(value);
            case Str _, Binary _ -> Optional.empty();
        };
    }
}
