package dev.javax.sonaudio.format.flac;

import java.util.List;

public record SeekTable(BlockHeader header, List<SeekPoint> seekPoints) implements Block {}
