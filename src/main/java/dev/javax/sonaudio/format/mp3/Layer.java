package dev.javax.sonaudio.format.mp3;

import dev.javax.sonaudio.exception.InvalidFormatException;

/**
 * The MPEG audio layers a frame may be coded in (ISO/IEC 11172-3 section 2.4.2.3). Each codes 32 subbands: Layers I
 * and II code their samples directly, and Layer III codes each subband's frequency lines.
 */
public enum Layer {
    I,
    II,
    III;

    /**
     * @param layerBits a frame header's layer field, which counts down from 3 for Layer I, and of which 0 is reserved
     */
    static Layer fromBits(final int layerBits) throws InvalidFormatException {
        return switch (layerBits) {
            case 0b11 -> I;
            case 0b10 -> II;
            case 0b01 -> III;
            default -> throw new InvalidFormatException("MPEG audio frame's layer is reserved!");
        };
    }
}
