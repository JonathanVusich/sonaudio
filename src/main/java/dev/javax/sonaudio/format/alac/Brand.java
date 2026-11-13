package dev.javax.sonaudio.format.alac;

/**
 * The kind of MPEG-4 file a file's {@code ftyp} box says it is, which sets how its audio sample entries are laid out.
 */
enum Brand {
    /**
     * An ISO base media file, such as an {@code .m4a} file.
     */
    ISO,
    /**
     * A QuickTime file, or a file that doesn't say. FFmpeg also takes a file that lists QuickTime among its compatible
     * brands for one.
     */
    QUICKTIME
}
