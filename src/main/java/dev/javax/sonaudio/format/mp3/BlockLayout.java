package dev.javax.sonaudio.format.mp3;

/**
 * Which blocks a granule's scale factors cover: long blocks (block types 0, 1 and 3), short blocks (type 2), or short
 * blocks whose lowest bands are long (type 2 with the mixed block flag).
 */
enum BlockLayout {
    LONG,
    SHORT,
    MIXED
}
