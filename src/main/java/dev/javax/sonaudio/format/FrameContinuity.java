package dev.javax.sonaudio.format;

/**
 * Whether a decoder dropped a frame because it couldn't decode it before the one it gives. FFmpeg returns an error for
 * such a frame, which stops AcoustID's fpcalc from reading any further, though FFmpeg's command line goes on.
 */
public enum FrameContinuity {
    CONTINUOUS,
    AFTER_DECODING_ERROR
}
