package dev.javax.sonaudio.samples;

import java.util.OptionalDouble;

/**
 * The Opus samples of FFmpeg's FATE suite (fate-suite.ffmpeg.org/opus), with what {@code tests/fate/opus.mak} at
 * FFmpeg's n9.0.2 tag compares them by. FATE runs {@code ffmpeg -i <name>.mka -f s16le -af aresample -} and
 * {@code tiny_psnr <reference> <output> s16 <shift>} on the result: it passes when the standard deviation of the
 * samples' differences is within {@value FateSamples#STDDEV_FUZZ} of the target, and the files, after the shift's
 * bytes are skipped in the reference, have the same size. The references are the newest vectors of RFC 8251
 * ({@code testvector05_v2.dec} and {@code testvector06_v2.dec}, the others' {@code .dec}). The reference image's
 * FFmpeg 9.0.2 meets every target, as the script that makes the FATE references was checked to.
 */
public enum FateOpusVector {
    TESTVECTOR01("testvector01", "testvector01.dec", 0),
    TESTVECTOR02("testvector02", "testvector02.dec", 191),
    TESTVECTOR03("testvector03", "testvector03.dec", 139),
    TESTVECTOR04("testvector04", "testvector04.dec", 119),
    TESTVECTOR05("testvector05", "testvector05_v2.dec", 108),
    TESTVECTOR06("testvector06", "testvector06_v2.dec", 106),
    TESTVECTOR07("testvector07", "testvector07.dec", 0),
    TESTVECTOR08("testvector08", "testvector08.dec", 6),
    TESTVECTOR09("testvector09", "testvector09.dec", 0),
    TESTVECTOR10("testvector10", "testvector10.dec", 38),
    TESTVECTOR11("testvector11", "testvector11.dec", 0),
    TESTVECTOR12("testvector12", "testvector12.dec", 160),
    SILK_LBRR("silk-lbrr", "silk-lbrr.dec", 0),
    // FATE skips the first 1440 bytes of the reference (CMP_SHIFT = 1440): 120 frames of 6 channels.
    TRON_6CH_TINYPKTS("tron.6ch.tinypkts", "tron.6ch.tinypkts.dec", 0, 1440),
    // FATE has no case for this sample, so it has no target.
    SILK_LBRR_MONO("silk-lbrr-mono", "silk-lbrr-mono.dec");

    private enum FateCase {
        RUN,
        NOT_RUN
    }

    private final String sampleName;
    private final String referenceFileName;
    private final FateCase fateCase;
    private final double cmpTarget;
    private final int cmpShiftBytes;

    FateOpusVector(final String sampleName, final String referenceFileName) {
        this.sampleName = sampleName;
        this.referenceFileName = referenceFileName;
        this.fateCase = FateCase.NOT_RUN;
        this.cmpTarget = 0;
        this.cmpShiftBytes = 0;
    }

    FateOpusVector(final String sampleName, final String referenceFileName, final double cmpTarget) {
        this(sampleName, referenceFileName, cmpTarget, 0);
    }

    FateOpusVector(
            final String sampleName, final String referenceFileName, final double cmpTarget, final int cmpShiftBytes) {
        this.sampleName = sampleName;
        this.referenceFileName = referenceFileName;
        this.fateCase = FateCase.RUN;
        this.cmpTarget = cmpTarget;
        this.cmpShiftBytes = cmpShiftBytes;
    }

    /**
     * @return the name FATE gives the sample, without an extension: {@code testvector01}, {@code tron.6ch.tinypkts}
     */
    public String sampleName() {
        return sampleName;
    }

    /**
     * @return the file name of RFC 8251's decode, under {@code opus/} in the FATE samples
     */
    public String referenceFileName() {
        return referenceFileName;
    }

    /**
     * @return FATE's {@code CMP_TARGET}: the standard deviation of the decoder's output from the reference's, in
     *     16-bit sample units, or nothing for a sample that FATE doesn't run
     */
    public OptionalDouble cmpTarget() {
        return switch (fateCase) {
            case RUN -> OptionalDouble.of(cmpTarget);
            case NOT_RUN -> OptionalDouble.empty();
        };
    }

    /**
     * @return FATE's {@code CMP_SHIFT}: the bytes of the reference's start that tiny_psnr skips
     */
    public int cmpShiftBytes() {
        return cmpShiftBytes;
    }
}
