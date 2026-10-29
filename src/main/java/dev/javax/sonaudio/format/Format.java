package dev.javax.sonaudio.format;

import java.util.Arrays;
import java.util.List;
import lombok.Getter;

@Getter
public enum Format {
    FLAC("flac"),
    // MPEG audio of every layer, which FFmpeg reads whatever its extension.
    MP3("mp3", "mp2", "mp1");

    private final List<String> extensions;

    Format(final String... extensions) {
        this.extensions = List.of(extensions);
    }

    /**
     * @return the extensions of every format it can read, lowercase and without the dot
     */
    public static List<String> supportedExtensions() {
        return Arrays.stream(values())
                .flatMap(format -> format.getExtensions().stream())
                .toList();
    }
}
