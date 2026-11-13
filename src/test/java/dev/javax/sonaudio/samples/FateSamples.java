package dev.javax.sonaudio.samples;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import lombok.experimental.UtilityClass;

/**
 * Where FFmpeg's FATE samples and their references are, for the tests that check the decoders against FATE's own test
 * cases. The directory is the one the {@code FATE_SAMPLES} environment variable names, or else {@code .fate-samples/}
 * at the project root, and must have been prepared by {@code reference/prepare-fate.sh} (which leaves {@code
 * sonaudio/STAMP} in it). Each accessor skips the test that calls it (a JUnit assumption) when there is no such
 * directory, as on CI, which doesn't prepare one.
 *
 * <p>Under the directory, the samples are laid out as on fate-suite.ffmpeg.org ({@code opus/testvector01.mka},
 * {@code opus/testvector01.dec}, {@code lossless-audio/inside.m4a}), and {@code sonaudio/} has what the reference image
 * made from them: each Opus {@code .mka} remuxed to an Ogg {@code .opus} with the same packets, FFmpeg's decodes
 * ({@code .s16le}, FATE's command), FATE's ALAC encodes and the decode MD5 of {@code inside.m4a}.
 */
@UtilityClass
public class FateSamples {
    public static final String ENVIRONMENT_VARIABLE = "FATE_SAMPLES";

    /** FATE's {@code FUZZ} for the Opus cases: the target's tolerance, in 16-bit sample units. */
    public static final int STDDEV_FUZZ = 3;

    /** The MD5 of {@code inside.m4a}'s decode, from {@code tests/ref/fate/lossless-alac}. */
    public static final String INSIDE_M4A_MD5 = "d0beb768d860b4776358077dd9fcb1e9";

    private static final String PREPARED_MARKER = "sonaudio/STAMP";

    /**
     * @return the prepared directory, or skips the test
     */
    public static Path directory() {
        final var environmentValue = System.getenv(ENVIRONMENT_VARIABLE);
        final var directory = chosenDirectory(environmentValue);
        final var prepared = Files.exists(directory.resolve(PREPARED_MARKER));
        assumeTrue(
                prepared,
                "no FATE samples prepared in " + directory + ": run reference/prepare-fate.sh, or set "
                        + ENVIRONMENT_VARIABLE);
        return directory;
    }

    private static Path chosenDirectory(final String environmentValue) {
        if (environmentValue == null || environmentValue.isBlank()) {
            return Path.of("").toAbsolutePath().resolve(".fate-samples");
        }
        return Path.of(environmentValue);
    }

    /**
     * @return the Matroska file FATE has for the vector
     */
    public static Path matroskaFile(final FateOpusVector vector) {
        return directory().resolve("opus").resolve(vector.sampleName() + ".mka");
    }

    /**
     * @return the vector as an Ogg Opus file, remuxed without re-encoding: its packets are the Matroska file's
     */
    public static Path opusFile(final FateOpusVector vector) {
        return directory().resolve("sonaudio").resolve(vector.sampleName() + ".opus");
    }

    /**
     * @return FFmpeg's decode of the Matroska file by FATE's command ({@code -f s16le -af aresample}): raw
     *     little-endian 16-bit samples at 48 kHz, which FATE's targets are measured against
     */
    public static Path ffmpegPcm(final FateOpusVector vector) {
        return directory().resolve("sonaudio").resolve(vector.sampleName() + ".s16le");
    }

    /**
     * @return FFmpeg's decode of {@link #opusFile}, which is {@link #ffmpegPcm}'s file except for the vectors whose Ogg
     *     file ends or starts a few samples differently ({@code silk-lbrr-mono}, {@code tron.6ch.tinypkts}: Matroska
     *     times packets in milliseconds), which have a file of their own
     */
    public static Path ffmpegPcmOfOpusFile(final FateOpusVector vector) {
        final var own = directory().resolve("sonaudio").resolve(vector.sampleName() + ".ogg.s16le");
        if (Files.exists(own)) {
            return own;
        }
        return ffmpegPcm(vector);
    }

    /**
     * @return RFC 8251's decode of the vector, which FATE compares FFmpeg's output to
     */
    public static Path rfcDecoded(final FateOpusVector vector) {
        return directory().resolve("opus").resolve(vector.referenceFileName());
    }

    /**
     * @return the encode of the FATE ALAC case
     */
    public static Path alacFile(final FateAlacCase fateCase) {
        return directory().resolve("sonaudio").resolve(fateCase.fileName());
    }

    /**
     * @return the samples that the FATE ALAC case must decode to: raw little-endian 16 or 24-bit
     */
    public static Path alacReferencePcm(final FateAlacCase fateCase) {
        return directory().resolve("sonaudio").resolve(fateCase.referenceFileName());
    }

    /**
     * @return {@code lossless-audio/inside.m4a}, the file of FATE's {@code lossless-alac} case
     */
    public static Path insideFile() {
        return directory().resolve("lossless-audio").resolve("inside.m4a");
    }

    /**
     * @return FFmpeg's decode of {@link #insideFile} (raw 16-bit samples), whose MD5 is {@link #INSIDE_M4A_MD5}
     */
    public static Path insidePcm() {
        return directory().resolve("sonaudio").resolve("inside.s16le");
    }

    /**
     * @return the output of {@code fpcalc} for a file in {@code sonaudio/}, or for {@code inside.m4a}, named
     *     {@code <file name>.fpcalc.txt} ({@code DURATION=} and {@code FINGERPRINT=} lines): {@code testvector01.opus},
     *     {@code alac-16-level-0.m4a}, {@code inside.m4a}. Files that fpcalc couldn't fingerprint, as they're too short
     *     ({@code silk-lbrr.opus}, {@code silk-lbrr-mono.opus}), have none.
     */
    public static Path fpcalcOutput(final String fileName) {
        return directory().resolve("sonaudio").resolve(fileName + ".fpcalc.txt");
    }
}
