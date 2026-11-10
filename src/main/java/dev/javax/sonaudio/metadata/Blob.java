package dev.javax.sonaudio.metadata;

/**
 * Binary data a tag holds, which never changes once it's made.
 */
public final class Blob {

    private final byte[] bytes;

    /**
     * Makes a blob of the bytes given, which it keeps rather than copies.
     *
     * @param bytes which the blob keeps: the caller makes them for it and doesn't touch them again
     */
    public Blob(final byte[] bytes) {
        this.bytes = bytes;
    }
}
