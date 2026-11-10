package dev.javax.sonaudio.format.flac;

record SeekPoint(long sampleNumber, long frameOffset, short samplesInFrame) {}
