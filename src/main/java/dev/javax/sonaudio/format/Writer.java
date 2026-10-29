package dev.javax.sonaudio.format;

import java.io.IOException;
import java.io.OutputStream;
import dev.javax.sonaudio.metadata.RawMetadata;

public interface Writer {

    void writeMetadata(RawMetadata rawMetadata, OutputStream outputStream) throws IOException;

    Format format();
}
