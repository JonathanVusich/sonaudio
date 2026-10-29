package dev.javax.sonaudio.format.mp3;

import java.util.List;
import dev.javax.sonaudio.format.mp3.Id3Frame.TextFrame;

/**
 * @param size how many bytes the tag takes at the end of the file: {@link Id3v1Reader#TAG_SIZE}, or 0 without one
 * @param frames the tag's fields that have a value, as the ID3v2.3 frames that hold the same data
 */
record Id3v1Tag(long size, List<TextFrame> frames) {}
