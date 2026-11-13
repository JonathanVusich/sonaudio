package dev.javax.sonaudio.format.opus;

/**
 * What {@link OpusParser} finds when it reads an Ogg page: one, or the end of the file at a page boundary.
 */
sealed interface NextPage {

    /**
     * @param page the page read
     */
    record Found(OggPage page) implements NextPage {}

    /**
     * The file ended where a page would have started.
     */
    record NoMorePages() implements NextPage {}
}
