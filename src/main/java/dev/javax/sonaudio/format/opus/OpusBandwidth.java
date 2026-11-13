package dev.javax.sonaudio.format.opus;

/**
 * The audio bandwidth a packet's TOC byte gives (RFC 6716 section 3.1, table 2), with what the decoders need to know
 * of each.
 */
enum OpusBandwidth {
    NARROWBAND(8000, 13),
    MEDIUMBAND(12000, 17),
    WIDEBAND(16000, 17),
    SUPERWIDEBAND(16000, 19),
    FULLBAND(16000, 21);

    private final int silkSampleRate;
    private final int celtEndBand;

    OpusBandwidth(final int silkSampleRate, final int celtEndBand) {
        this.silkSampleRate = silkSampleRate;
        this.celtEndBand = celtEndBand;
    }

    /**
     * @return the rate SILK decodes this bandwidth at: 8, 12 or 16 kHz, where super-wideband and fullband's SILK layer
     *     is wideband's
     */
    int silkSampleRate() {
        return silkSampleRate;
    }

    /**
     * @return the bandwidth SILK is told, which is wideband for the bandwidths above it
     */
    OpusBandwidth silkBandwidth() {
        if (this == SUPERWIDEBAND || this == FULLBAND) {
            return WIDEBAND;
        }
        return this;
    }

    /**
     * @return the CELT band that CELT stops coding at: the first above this bandwidth
     */
    int celtEndBand() {
        return celtEndBand;
    }
}
