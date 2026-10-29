package dev.javax.sonaudio.fingerprint;

import dev.javax.bitstream.BitOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.List;
import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.FloatPcmAudio;
import dev.javax.sonaudio.format.IntPcmAudio;

/**
 * The Chromaprint fingerprint of the first two minutes of a file's audio, which AcoustID identifies recordings by. It
 * follows Chromaprint's default algorithm, as {@code fpcalc} computes it, but resamples the audio with Sona's own
 * filter, so the two may differ in a few bits.
 */
public final class Fingerprint {

    // Chromaprint's number for its default algorithm, which a compressed fingerprint starts with.
    private static final int ALGORITHM = 1;
    private static final int[] GRAY_CODE = {0, 1, 3, 2};
    // Chromaprint's classifiers for its default algorithm, trained on pairs of recordings that match and that don't.
    private static final List<Classifier> CLASSIFIERS = List.of(
            new Classifier(FilterShape.WHOLE, 4, 3, 15, 1.98215, 2.35817, 2.63523),
            new Classifier(FilterShape.BAND_THIRDS, 4, 6, 15, -1.03809, -0.651211, -0.282167),
            new Classifier(FilterShape.BAND_HALVES, 0, 4, 16, -0.298702, 0.119262, 0.558497),
            new Classifier(FilterShape.QUARTERS, 8, 2, 12, -0.105439, 0.0153946, 0.135898),
            new Classifier(FilterShape.QUARTERS, 4, 4, 8, -0.142891, 0.0258736, 0.200632),
            new Classifier(FilterShape.BAND_THIRDS, 0, 3, 5, -0.826319, -0.590612, -0.368214),
            new Classifier(FilterShape.BAND_HALVES, 2, 2, 9, -0.557409, -0.233035, 0.0534525),
            new Classifier(FilterShape.ROW_HALVES, 7, 3, 4, -0.0646826, 0.00620476, 0.0784847),
            new Classifier(FilterShape.ROW_HALVES, 6, 2, 16, -0.192387, -0.029699, 0.215855),
            new Classifier(FilterShape.ROW_HALVES, 1, 3, 2, -0.0397818, -0.00568076, 0.0292026),
            new Classifier(FilterShape.ROW_THIRDS, 10, 1, 15, -0.53823, -0.369934, -0.190235),
            new Classifier(FilterShape.QUARTERS, 6, 2, 10, -0.124877, 0.0296483, 0.139239),
            new Classifier(FilterShape.ROW_HALVES, 1, 1, 14, -0.101475, 0.0225617, 0.231971),
            new Classifier(FilterShape.QUARTERS, 5, 6, 4, -0.0799915, -0.00729616, 0.063262),
            new Classifier(FilterShape.BAND_HALVES, 9, 2, 12, -0.272556, 0.019424, 0.302559),
            new Classifier(FilterShape.QUARTERS, 4, 2, 14, -0.164292, -0.0321188, 0.0846339));
    private static final int MAX_ROWS =
            CLASSIFIERS.stream().mapToInt(Classifier::rows).max().orElseThrow();

    // Compressed fingerprints store the gaps between set bits in three bits, and those of 7 or more in five more.
    private static final int NORMAL_BITS = 3;
    private static final int EXCEPTION_BITS = 5;
    private static final int MAX_NORMAL_GAP = 7;

    // One 32-bit sub-fingerprint per row of the chroma image the classifiers fit in, about eight a second.
    private final int[] subFingerprints;

    /**
     * Decodes only as much of the audio as it needs.
     *
     * @throws IOException when the audio can't be read, or ends before a frame does
     * @throws InvalidFormatException when the audio is corrupt
     */
    public Fingerprint(final IntPcmAudio audio) throws IOException, InvalidFormatException {
        this.subFingerprints = subFingerprints(new Signal(audio));
    }

    /**
     * Decodes only as much of the audio as it needs.
     *
     * @throws IOException when the audio can't be read, or ends before a frame does
     * @throws InvalidFormatException when the audio is corrupt
     */
    public Fingerprint(final FloatPcmAudio audio) throws IOException, InvalidFormatException {
        this.subFingerprints = subFingerprints(new Signal(audio));
    }

    private static int[] subFingerprints(final Signal signal) {
        final var chroma = new ChromaImage(signal);
        final var image = new IntegralImage(chroma);
        return subFingerprints(image);
    }

    /**
     * @return whether the audio was too short to fingerprint, which AcoustID can't look up
     */
    public boolean isEmpty() {
        return subFingerprints.length == 0;
    }

    /**
     * @return the fingerprint compressed and base64-encoded, as {@code fpcalc} prints it and AcoustID takes it
     */
    public String compressed() {
        final var outputStream = new ByteArrayOutputStream();
        // Chromaprint packs each value least significant bit first.
        final var bitStream = BitOutputStream.wrap(outputStream, ByteOrder.LITTLE_ENDIAN);
        try {
            writeHeader(bitStream);
            writeGaps(bitStream, bitGaps());
        } catch (IOException e) {
            throw new UncheckedIOException("A ByteArrayOutputStream can't fail to write", e);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(outputStream.toByteArray());
    }

    // The algorithm, then the number of sub-fingerprints in three bytes, most significant first.
    private void writeHeader(final BitOutputStream bitStream) throws IOException {
        bitStream.writeBits(ALGORITHM, Byte.SIZE);
        for (var shift = 2 * Byte.SIZE; shift >= 0; shift -= Byte.SIZE) {
            bitStream.writeBits(subFingerprints.length >> shift, Byte.SIZE);
        }
    }

    // Every gap in three bits, then, from the next byte on, the rest of each large one in five more.
    private static void writeGaps(final BitOutputStream bitStream, final byte[] gaps) throws IOException {
        for (final var gap : gaps) {
            bitStream.writeBits(Math.min(gap, MAX_NORMAL_GAP), NORMAL_BITS);
        }
        // Flushing fills the rest of the current byte with zeros.
        bitStream.flush();
        for (final var gap : gaps) {
            if (gap >= MAX_NORMAL_GAP) {
                bitStream.writeBits(gap - MAX_NORMAL_GAP, EXCEPTION_BITS);
            }
        }
        bitStream.flush();
    }

    private static int[] subFingerprints(final IntegralImage image) {
        final var count = Math.max(0, image.rowCount() - MAX_ROWS + 1);
        final var subFingerprints = new int[count];
        for (var row = 0; row < count; row++) {
            var bits = 0;
            for (final var classifier : CLASSIFIERS) {
                bits = (bits << 2) | GRAY_CODE[classifier.classify(image, row)];
            }
            subFingerprints[row] = bits;
        }
        return subFingerprints;
    }

    // The bits that change from each sub-fingerprint to the next, as the gaps between them, each one's list ended by 0.
    private byte[] bitGaps() {
        final var gaps = new ByteArrayOutputStream();
        var previous = 0;
        for (final var subFingerprint : subFingerprints) {
            var changed = subFingerprint ^ previous;
            var lastBit = 0;
            while (changed != 0) {
                final var bit = Integer.numberOfTrailingZeros(changed) + 1;
                gaps.write(bit - lastBit);
                lastBit = bit;
                changed &= changed - 1;
            }
            gaps.write(0);
            previous = subFingerprint;
        }
        return gaps.toByteArray();
    }
}
