package dev.javax.sonaudio.format.alac;

import java.util.List;

/**
 * What the parser reads from a file's {@code moov} box.
 *
 * @param timeScale the ticks per second the movie's times are in, which its tracks' edit lists use
 * @param tracks the movie's tracks, in the order the file has them
 * @param items the values of the tags in the movie's {@code ilst} box, in the order the file has them
 */
record Movie(long timeScale, List<Track> tracks, List<MetadataItem> items) {}
