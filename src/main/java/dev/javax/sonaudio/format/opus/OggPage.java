package dev.javax.sonaudio.format.opus;

import java.util.List;

/**
 * An Ogg page (RFC 3533 section 6) whose CRC-32 has been checked, with its payload cut into the packet fragments its
 * segment table gives.
 *
 * @param continuation whether the first fragment goes on from the page before
 * @param start whether the page starts its logical stream
 * @param end whether the page ends its logical stream
 * @param granule the page's granule position
 * @param serial the serial number of the page's logical stream
 * @param sequence the page's sequence number in its stream
 * @param fragments the packets' bytes in the page, in order
 */
record OggPage(
        PageContinuation continuation,
        PageStart start,
        PageEnd end,
        PageGranule granule,
        int serial,
        int sequence,
        List<PacketFragment> fragments) {}
