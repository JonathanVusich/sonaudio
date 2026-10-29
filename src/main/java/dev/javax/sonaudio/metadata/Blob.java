package dev.javax.sonaudio.metadata;

import java.util.Arrays;

public final class Blob {

    private final byte[] bytes;

    private Blob(final byte[] bytes) {
        this.bytes = bytes;
    }

    public static Blob copyOf(final byte[] bytes) {
        return new Blob(Arrays.copyOf(bytes, bytes.length));
    }
}
