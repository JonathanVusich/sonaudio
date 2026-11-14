package dev.javax.sonaudio.format.flac;

import dev.javax.sonaudio.internal.VorbisField;
import java.util.List;

record VorbisComment(BlockHeader header, String vendor, List<VorbisField> fields) implements Block {}
