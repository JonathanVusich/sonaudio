package dev.javax.sonaudio.format.opus;

/**
 * Whether the packet a run of an Ogg page's segments belongs to ends with them, or goes on in the next page: a
 * segment of fewer than 255 bytes ends a packet.
 */
enum FragmentEnd {
    COMPLETES_PACKET,
    CONTINUES_PACKET
}
