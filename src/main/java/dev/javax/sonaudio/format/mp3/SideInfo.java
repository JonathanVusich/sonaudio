package dev.javax.sonaudio.format.mp3;

import java.util.List;

/**
 * A Layer III frame's side information, which follows its header and any CRC (ISO/IEC 11172-3 section 2.4.1.7,
 * ISO/IEC 13818-3 section 2.4.1.7), as {@link Mp3Decoder} reads it: two granules in MPEG-1, and one in MPEG-2, each
 * with one or two channels.
 *
 * @param mainDataBegin how many bytes before the frame's own main data its first granule starts, in earlier frames'
 * @param granules each granule's channels
 */
record SideInfo(int mainDataBegin, List<List<GranuleChannel>> granules) {}
