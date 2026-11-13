package dev.javax.sonaudio.internal;

import java.util.List;

/**
 * The vendor and fields of a Vorbis comment (RFC 9639 section 8.6, RFC 7845 section 5.2).
 *
 * @param vendor the name of the software that wrote the comment
 * @param fields the comment's fields, in file order
 */
public record VorbisComments(String vendor, List<VorbisField> fields) {}
