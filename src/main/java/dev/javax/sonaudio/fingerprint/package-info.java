/**
 * Chromaprint, as AcoustID identifies recordings by. A {@link dev.javax.sonaudio.fingerprint.Fingerprint} reads the
 * first two minutes of decoded audio, brings it to 16-bit mono at 11025 Hz as FFmpeg's libswresample does for
 * {@code fpcalc}, and runs Chromaprint's default algorithm over it.
 * {@link dev.javax.sonaudio.fingerprint.Fingerprint#compressed} is the form {@code fpcalc} prints and AcoustID's API
 * takes.
 */
package dev.javax.sonaudio.fingerprint;
