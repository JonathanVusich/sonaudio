package dev.javax.sonaudio.internal;

import dev.javax.sonaudio.metadata.RawMetadataBuilder;

/**
 * One {@code NAME=value} field of a Vorbis comment, as FLAC's VORBIS_COMMENT block and Opus's {@code OpusTags} packet
 * keep their tags.
 *
 * @param name the field's name, in the case the file wrote it
 * @param content the field's value
 */
public record VorbisField(String name, String content) {

    /**
     * Adds this field to the tags being read, as the {@link dev.javax.sonaudio.metadata.Tag} its name maps to, or under
     * its own name when sonaudio has no tag for it.
     *
     * @param builder the tags of the file being parsed
     */
    public void addTag(final RawMetadataBuilder builder) {
        final var fieldName = name.toLowerCase();
        final var fieldTag = VorbisTag.fieldTag(fieldName);
        switch (fieldTag) {
            case VorbisFieldTag.Known(final var vorbisTag) ->
                builder.addTag(vorbisTag.getTag(), vorbisTag.toTagValue(content));
            case VorbisFieldTag.Unknown(final var unknownName) -> builder.addUnknownTag(unknownName, content);
        }
    }
}
