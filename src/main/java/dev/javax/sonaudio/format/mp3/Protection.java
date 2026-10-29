package dev.javax.sonaudio.format.mp3;

/**
 * Whether a CRC of the header and side information follows a frame's header, as its protection bit says.
 */
public enum Protection {
    CRC,
    NONE;

    private static final int CRC_SIZE = 2;

    // The bit is 0 when the frame has a CRC.
    static Protection fromBit(final long protectionBit) {
        if (protectionBit == 0) {
            return CRC;
        }
        return NONE;
    }

    /**
     * @return how many bytes the CRC takes after the header
     */
    int crcSize() {
        return switch (this) {
            case CRC -> CRC_SIZE;
            case NONE -> 0;
        };
    }
}
