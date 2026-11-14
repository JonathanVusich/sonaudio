package dev.javax.sonaudio.samples;

/**
 * The ALAC encodes of FATE's {@code fate-alac-*} cases ({@code tests/fate/alac.mak}): the WAV files
 * {@code audio-reference/luckynight_2ch_44kHz_s16.wav} and {@code divertimenti_2ch_96kHz_s24.wav} encoded with
 * {@code -c:a alac} at each compression level and with {@code -min_prediction_order 1 -max_prediction_order 30}, which
 * FATE decodes and requires to match its source exactly. The encodes are in {@code .m4a} files (FATE writes mov), made
 * by the reference image's FFmpeg 9.0.2, and their reference is their source's samples as raw little-endian s16le or
 * s24le.
 */
public enum FateAlacCase {
    ALAC_16_LEVEL_0("luckynight_2ch_44kHz_s16.s16le"),
    ALAC_16_LEVEL_1("luckynight_2ch_44kHz_s16.s16le"),
    ALAC_16_LEVEL_2("luckynight_2ch_44kHz_s16.s16le"),
    ALAC_16_LPC_ORDERS("luckynight_2ch_44kHz_s16.s16le"),
    ALAC_24_LEVEL_0("divertimenti_2ch_96kHz_s24.s24le"),
    ALAC_24_LEVEL_1("divertimenti_2ch_96kHz_s24.s24le"),
    ALAC_24_LEVEL_2("divertimenti_2ch_96kHz_s24.s24le"),
    ALAC_24_LPC_ORDERS("divertimenti_2ch_96kHz_s24.s24le");

    private final String referenceFileName;

    FateAlacCase(final String referenceFileName) {
        this.referenceFileName = referenceFileName;
    }

    /**
     * @return the encode's file name, under {@code sonaudio/} in the prepared directory
     */
    public String fileName() {
        return name().toLowerCase().replace('_', '-') + ".m4a";
    }

    /**
     * @return the file name of the raw samples it decodes to, under {@code sonaudio/}
     */
    public String referenceFileName() {
        return referenceFileName;
    }
}
