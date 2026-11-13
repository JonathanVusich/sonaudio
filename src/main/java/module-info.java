/**
 * Pure-Java readers for audio files, the tag model they fill in, and Chromaprint fingerprints.
 *
 * <ul>
 *   <li>{@link dev.javax.sonaudio.format}: what every format shares, from parsers and writers to the decoded audio.
 *   <li>{@link dev.javax.sonaudio.format.flac}: FLAC's parser, decoder and writer.
 *   <li>{@link dev.javax.sonaudio.format.mp3}: MPEG audio's parser and decoder, of Layers I, II and III.
 *   <li>{@link dev.javax.sonaudio.format.alac}: ALAC's parser and decoder, for the audio of an MPEG-4 file.
 *   <li>{@link dev.javax.sonaudio.format.opus}: Opus's parser and decoder, for the audio of an Ogg file.
 *   <li>{@link dev.javax.sonaudio.metadata}: the format-agnostic tags a parser reads.
 *   <li>{@link dev.javax.sonaudio.fingerprint}: Chromaprint fingerprints, as AcoustID identifies recordings by.
 *   <li>{@link dev.javax.sonaudio.exception}: the exception a parser or decoder throws for a malformed file.
 * </ul>
 */
module dev.javax.sonaudio {
    requires bitstream.main;
    requires static lombok;

    exports dev.javax.sonaudio.exception;
    exports dev.javax.sonaudio.fingerprint;
    exports dev.javax.sonaudio.format;
    exports dev.javax.sonaudio.format.alac;
    exports dev.javax.sonaudio.format.flac;
    exports dev.javax.sonaudio.format.mp3;
    exports dev.javax.sonaudio.format.opus;
    exports dev.javax.sonaudio.metadata;
}
