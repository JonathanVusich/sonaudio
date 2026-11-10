package dev.javax.sonaudio.format.flac;

/**
 * What a Vorbis comment field holds: one of Sona's tags, or a tag Sona keeps under its own name.
 */
sealed interface FieldTag permits FieldTag.Known, FieldTag.Unknown {

    record Known(FlacTag tag) implements FieldTag {}

    /**
     * @param name the field's name, in lower case
     */
    record Unknown(String name) implements FieldTag {}
}
