package dev.javax.sonaudio.format.opus;

/**
 * Whether a packet says where it ends. All but the last stream of a multistream packet do (RFC 6716 appendix B),
 * with one more length after the others.
 */
enum Delimiting {
    SELF_DELIMITED,
    UNDELIMITED
}
