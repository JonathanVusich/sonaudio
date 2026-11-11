package dev.javax.sonaudio.format.flac;

import java.util.List;

record VorbisComment(BlockHeader header, String vendor, List<Field> fields) implements Block {}
