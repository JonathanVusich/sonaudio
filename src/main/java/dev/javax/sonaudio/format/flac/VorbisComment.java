package dev.javax.sonaudio.format.flac;

import java.util.List;

public record VorbisComment(BlockHeader header, String vendor, List<Field> fields) implements Block {}
