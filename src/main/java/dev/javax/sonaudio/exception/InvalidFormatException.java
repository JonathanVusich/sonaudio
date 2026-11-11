package dev.javax.sonaudio.exception;

/**
 * Thrown when a file is malformed, or isn't of the format it was read as. Parsers and decoders throw it rather than
 * skip or guess at what they can't read.
 */
public final class InvalidFormatException extends Exception {

    /**
     * @param message what was wrong with the file
     */
    public InvalidFormatException(final String message) {
        super(message);
    }
}
