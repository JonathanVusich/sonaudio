package dev.javax.sonaudio.format.opus;

/**
 * The granule position of an Ogg page, which for Opus counts the 48 kHz samples up to the end of the last packet that
 * finishes on the page, pre-skip included (RFC 7845 section 4). A page where no packet finishes has none, which Ogg
 * writes as all ones.
 */
sealed interface PageGranule {

    /**
     * @param samples the position, from 0
     */
    record Positioned(long samples) implements PageGranule {}

    /**
     * A page that no packet finishes on.
     */
    record Unpositioned() implements PageGranule {}
}
