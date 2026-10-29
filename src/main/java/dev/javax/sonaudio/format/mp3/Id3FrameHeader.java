package dev.javax.sonaudio.format.mp3;

/**
 * @param frameId the frame's ID, which an ID3v2.2 frame Sona reads takes from the ID3v2.3 frame that replaced it
 * @param size how many bytes the frame takes after its header
 * @param formatFlags the second byte of the frame's flags, which ID3v2.2 frames don't have, so 0
 */
record Id3FrameHeader(String frameId, long size, int formatFlags) {}
