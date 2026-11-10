package dev.javax.sonaudio.metadata;

public final class Blob {

    private final byte[] bytes;

    /**
     * @param bytes which the blob keeps: the caller makes them for it and doesn't touch them again
     */
    public Blob(final byte[] bytes) {
        this.bytes = bytes;
    }
}
