package dev.javax.sonaudio.format;

/**
 * Whether a decoder, which reads its stream once, has handed out its frames yet.
 */
public enum Iteration {
    NOT_STARTED,
    STARTED
}
