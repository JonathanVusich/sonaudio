package dev.javax.sonaudio.metadata;

import java.util.Optional;

public sealed interface TagValue permits TagValue.Str, TagValue.Int, TagValue.Binary {

    record Str(String value) implements TagValue {}

    record Int(int value) implements TagValue {}

    record Binary(Blob blob) implements TagValue {}

    /**
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
     * @return the value if it is a string, or empty
     */
    default Optional<String> asString() {
        return switch (this) {
            case Str(String value) -> Optional.of(value);
            case Int _, Binary _ -> Optional.empty();
        };
    }

    /**
     * @return the value if it is a number, or empty
     */
    default Optional<Integer> asInt() {
        return switch (this) {
            case Int(int value) -> Optional.of(value);
            case Str _, Binary _ -> Optional.empty();
        };
    }
}
