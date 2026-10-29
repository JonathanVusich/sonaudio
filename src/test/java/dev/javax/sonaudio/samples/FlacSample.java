package dev.javax.sonaudio.samples;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

/**
 * Real-world FLAC files trimmed to their metadata blocks, with the audio frames removed.
 */
public enum FlacSample {
    SAMPLE_3("sample-3.flac"),
    COLDPLAY("coldplay_a_whisper.flac"),
    LUCAS_FLOYD("lucas_floyd_canyon.flac");

    private final String fileName;

    FlacSample(final String fileName) {
        this.fileName = fileName;
    }

    public String fileName() {
        return fileName;
    }

    public InputStream inputStream() {
        return FlacSample.class.getResourceAsStream(fileName);
    }

    /**
     * @return the file in memory, as parsers read it
     */
    public SeekableByteChannel channel() throws IOException {
        try (final var inputStream = inputStream()) {
            return new SeekableInMemoryByteChannel(inputStream.readAllBytes());
        }
    }
}
