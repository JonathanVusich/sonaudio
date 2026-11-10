package dev.javax.sonaudio.format;

import java.util.Arrays;
import java.util.List;
import lombok.Getter;

/**
 * The audio formats sonaudio reads, each with the file extensions it's known by.
 */
@Getter
public enum Format {
    /**
     * FLAC, as RFC 9639 specifies it.
     */
    FLAC("flac"),
    /**
     * MPEG-1, MPEG-2 and MPEG-2.5 audio of Layers I, II and III, which FFmpeg reads whatever its extension.
     */
    MP3("mp3", "mp2", "mp1");

    /**
     * The extensions of this format's files, lowercase and without the dot.
     */
    private final List<String> extensions;

    Format(final String... extensions) {
        this.extensions = List.of(extensions);
    }

    /**
     * Lists the extensions of every format, to tell which files can be read.
     *
     * @return the extensions of every format it can read, lowercase and without the dot
     */
    public static List<String> supportedExtensions() {
        return Arrays.stream(values())
                .flatMap(format -> format.getExtensions().stream())
                .toList();
    }
}
