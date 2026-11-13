package dev.javax.sonaudio.format.opus;

/**
 * Whether an Ogg stream's reader is still in the headers or has begun the audio.
 */
enum OggPhase {
    HEADERS,
    AUDIO
}
