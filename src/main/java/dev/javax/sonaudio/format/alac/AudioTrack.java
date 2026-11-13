package dev.javax.sonaudio.format.alac;

/**
 * The ALAC track the parser decodes.
 *
 * @param track the track
 * @param index how many tracks the movie has before it
 */
record AudioTrack(Track.Alac track, int index) {}
