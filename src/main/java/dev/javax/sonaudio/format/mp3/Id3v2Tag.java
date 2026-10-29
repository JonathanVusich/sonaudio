package dev.javax.sonaudio.format.mp3;

import java.util.List;

/**
 * @param size how many bytes the tag takes at the start of the file, with its header and footer
 */
record Id3v2Tag(long size, List<Id3Frame> frames) {}
