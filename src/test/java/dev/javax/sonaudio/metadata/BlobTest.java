package dev.javax.sonaudio.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BlobTest {

    @Test
    void bytesAreTheOnesItWasMadeOf() {
        final var blob = new Blob(new byte[] {1, 2, 3});

        assertThat(blob.bytes()).containsExactly(1, 2, 3);
    }

    @Test
    void changingTheBytesItGaveLeavesTheBlobAsItWas() {
        final var blob = new Blob(new byte[] {1, 2, 3});

        blob.bytes()[0] = 9;

        assertThat(blob.bytes()).containsExactly(1, 2, 3);
    }
}
