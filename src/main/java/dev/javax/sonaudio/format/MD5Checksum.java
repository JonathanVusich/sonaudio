package dev.javax.sonaudio.format;

import java.util.Arrays;
import java.util.HexFormat;

/**
 * An MD5 digest, as FLAC's STREAMINFO block keeps of its unencoded audio.
 *
 * @param checksum the 16 bytes of the MD5 digest, which the checksum keeps: the caller makes them for it and doesn't
 *     touch them again
 */
public record MD5Checksum(byte[] checksum) {

    /**
     * @return a copy of the digest's 16 bytes
     */
    @Override
    public byte[] checksum() {
        return checksum.clone();
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof MD5Checksum(final var otherChecksum) && Arrays.equals(checksum, otherChecksum);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(checksum);
    }

    @Override
    public String toString() {
        return HexFormat.of().formatHex(checksum);
    }
}
