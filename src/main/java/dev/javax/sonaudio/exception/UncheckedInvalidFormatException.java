package dev.javax.sonaudio.exception;

/**
 * Wraps an {@link InvalidFormatException} where a checked exception can't be thrown, as
 * {@link java.io.UncheckedIOException} does an {@code IOException}: from an {@link java.util.Iterator} of decoded audio.
 */
public final class UncheckedInvalidFormatException extends RuntimeException {

    private final InvalidFormatException cause;

    public UncheckedInvalidFormatException(final InvalidFormatException cause) {
        super(cause.getMessage(), cause);
        this.cause = cause;
    }

    @Override
    public InvalidFormatException getCause() {
        return cause;
    }
}
