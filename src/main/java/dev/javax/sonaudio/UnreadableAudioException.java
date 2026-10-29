package dev.javax.sonaudio;

/**
 * FFmpeg can't read a file as audio: it isn't in a format FFmpeg knows, it's too damaged to open, or it has no audio
 * stream or no decoder for it.
 */
public final class UnreadableAudioException extends Exception {

    public UnreadableAudioException(final String message) {
        super(message);
    }
}
