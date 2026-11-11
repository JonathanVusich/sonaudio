package dev.javax.sonaudio.format;

import dev.javax.sonaudio.metadata.RawMetadata;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes metadata in one format.
 */
public interface Writer {

    /**
     * Writes the start of a file of this format, up to where its audio would begin.
     *
     * @param rawMetadata the tags to write
     * @param outputStream where to write them, which the caller still owns
     * @throws IOException when the stream can't be written to
     */
    void writeMetadata(RawMetadata rawMetadata, OutputStream outputStream) throws IOException;

    /**
     * @return the format this writer writes
     */
    Format format();
}
