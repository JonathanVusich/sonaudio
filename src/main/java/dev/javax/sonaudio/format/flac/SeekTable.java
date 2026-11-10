package dev.javax.sonaudio.format.flac;

import java.util.List;

record SeekTable(BlockHeader header, List<SeekPoint> seekPoints) implements Block {}
