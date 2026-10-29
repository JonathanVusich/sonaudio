package dev.javax.sonaudio.format.flac;

public record SeekPoint(long sampleNumber, long frameOffset, short samplesInFrame) {}
