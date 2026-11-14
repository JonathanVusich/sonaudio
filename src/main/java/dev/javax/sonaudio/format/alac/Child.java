package dev.javax.sonaudio.format.alac;

import dev.javax.sonaudio.internal.BoundedInputStream;
import java.io.IOException;

/**
 * A box inside another, which is read from a stream that ends where the box does.
 *
 * @param header the box's header
 * @param payload the box's payload
 */
record Child(BoxHeader header, BoundedInputStream payload) {

    /**
     * Skips what the box has left unread, which a box may have after the parts the parser reads, so that the box
     * holding this one is at the next.
     *
     * @throws IOException when the stream can't be read
     */
    void finish() throws IOException {
        payload.skipAvailableBytes(payload.getRemaining());
    }
}
