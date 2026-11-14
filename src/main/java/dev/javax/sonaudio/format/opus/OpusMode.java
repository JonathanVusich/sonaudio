package dev.javax.sonaudio.format.opus;

/**
 * Which of Opus's two codecs a frame uses, as its packet's TOC byte says (RFC 6716 section 3.1).
 */
enum OpusMode {
    /**
     * SILK alone, for speech at up to wideband.
     */
    SILK,
    /**
     * SILK for the low band and CELT for the rest, at super-wideband and fullband.
     */
    HYBRID,
    /**
     * CELT alone.
     */
    CELT
}
