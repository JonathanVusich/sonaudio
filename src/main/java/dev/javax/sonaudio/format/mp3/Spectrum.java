package dev.javax.sonaudio.format.mp3;

import dev.javax.bitstream.BitInputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * A granule's 576 frequency lines for one channel: its Huffman-coded values, requantized (ISO/IEC 11172-3 sections
 * 2.4.3.4.4 to 2.4.3.4.7) as FFmpeg's floating-point decoder requantizes them, from its tables, in 32-bit floats. A
 * short block's lines are in the order they're coded: by band, then window. The decoder keeps one per channel, and
 * reads each granule into it in place, as it runs for every granule of the file.
 */
final class Spectrum {

    static final int LINES = FrameHeader.GRANULE_SIZE;
    // The filterbank's 32 subbands each hold 18 lines.
    static final int SUBBANDS = 32;
    static final int SUBBAND_LINES = 18;

    // The global gain of 210 leaves a value unscaled, and FFmpeg's exponents start 400 above it.
    private static final int GAIN_OFFSET = 210;
    private static final int EXPONENT_OFFSET = 400;
    // Each subblock gain step is 2 in 2^(1/4) steps of the global gain: a factor of 4.
    private static final int SUBBLOCK_GAIN_STEP = 8;
    // FFmpeg folds mid/side stereo's 1/sqrt(2) into the global gain, as 2 steps of 2^(1/4).
    private static final int MID_SIDE_GAIN = 2;
    private static final int MID_SIDE_BIT = 2;
    private static final int INTENSITY_BIT = 1;
    private static final int LINBITS_VALUE = 15;
    private static final int QUAD = 4;

    private final float[] lines = new float[LINES];
    // Each line's exponent, in steps of 2^(1/4): its gain, less its scale factor.
    private final int[] exponents = new int[LINES];
    // One short band's lines as they're reordered.
    private final float[] reorderBuffer = new float[LINES];
    private int nonZeroEnd;

    /**
     * Reads the granule's values for the channel, which follow its scale factors, until its part2_3_length is used up,
     * as FFmpeg reads them: a pair of big values is read whole if it starts before the channel's bits run out, the
     * lines after the last one read are 0, and count1's last quadruple is left out if it runs past them. FFmpeg also
     * stops where the frame's data ends, which a frame the file cuts short does before its part2_3_length.
     *
     * @param presentBits how many bits the file has for the channel, from where its scale factors start
     */
    void read(
            final BitInputStream bits,
            final FrameHeader header,
            final GranuleChannel granuleChannel,
            final ScaleFactors scaleFactors,
            final int presentBits)
            throws IOException {
        Arrays.fill(lines, 0);
        final var part3Length = granuleChannel.part23Length() - scaleFactors.part2Length();
        final var presentPart3Length = Math.min(part3Length, presentBits - scaleFactors.part2Length());
        computeExponents(header, granuleChannel, scaleFactors);
        final var bigValuesEnd = Math.min(2 * granuleChannel.bigValues(), LINES);
        final var bigValueBits = readBigValues(bits, header, granuleChannel, bigValuesEnd, presentPart3Length);
        nonZeroEnd = readCount1(
                bits,
                granuleChannel.count1Code(),
                bigValuesEnd,
                part3Length - bigValueBits,
                presentPart3Length - bigValueBits);
    }

    /**
     * Makes every line 0, for a granule whose bits start before the stream's main data does.
     */
    void clear() {
        Arrays.fill(lines, 0);
        nonZeroEnd = 0;
    }

    float line(final int index) {
        return lines[index];
    }

    void setLine(final int index, final float value) {
        lines[index] = value;
    }

    /**
     * @return the line after the last one that may not be 0
     */
    int nonZeroEnd() {
        return nonZeroEnd;
    }

    /**
     * @param end the line after the last one that may not be 0, such as after stereo processing spreads one channel's
     *     lines to the other
     */
    void setNonZeroEnd(final int end) {
        nonZeroEnd = end;
    }

    /**
     * Reorders a short or mixed block's short bands from the order they're coded in, each window's lines in turn, to
     * the order the IMDCT takes them in: each line's three windows in turn (ISO/IEC 11172-3 section 2.4.3.4.8).
     */
    void reorderShortBands(final FrameHeader header, final Windowing windowing) {
        final var layout = windowing.layout();
        if (layout == BlockLayout.LONG) {
            return;
        }
        final var bands = ScaleFactorBands.fromSampleRate(header.sampleRate());
        final var firstBand = firstShortBand(layout);
        var start = Windowing.WINDOWS * bands.shortBandStart(firstBand);
        for (var band = firstBand; band < ScaleFactorBands.SHORT_BANDS; band++) {
            final var width = bands.shortBandWidth(band);
            for (var line = 0; line < width; line++) {
                for (var window = 0; window < Windowing.WINDOWS; window++) {
                    reorderBuffer[Windowing.WINDOWS * line + window] = lines[start + window * width + line];
                }
            }
            System.arraycopy(reorderBuffer, 0, lines, start, Windowing.WINDOWS * width);
            start += Windowing.WINDOWS * width;
        }
        // Lines move up from the windows above, so any may now not be 0.
        nonZeroEnd = LINES;
    }

    /**
     * Undoes the aliasing the encoder's filterbank put between neighbouring subbands, with butterflies across each
     * boundary of the long blocks' subbands (ISO/IEC 11172-3 section 2.4.3.4.9).
     */
    void reduceAliasing(final Windowing windowing) {
        final var boundaries = switch (windowing.layout()) {
            case LONG -> SUBBANDS - 1;
            case MIXED -> 1;
            case SHORT -> 0;
        };
        for (var boundary = 1; boundary <= boundaries; boundary++) {
            final var first = SUBBAND_LINES * boundary;
            for (var butterfly = 0; butterfly < AliasCoefficients.COUNT; butterfly++) {
                final var below = lines[first - 1 - butterfly];
                final var above = lines[first + butterfly];
                final var cs = AliasCoefficients.floatCs(butterfly);
                final var ca = AliasCoefficients.floatCa(butterfly);
                lines[first - 1 - butterfly] = below * cs - above * ca;
                lines[first + butterfly] = below * ca + above * cs;
            }
        }
    }

    private void computeExponents(
            final FrameHeader header, final GranuleChannel granuleChannel, final ScaleFactors scaleFactors) {
        final var bands = ScaleFactorBands.fromSampleRate(header.sampleRate());
        final var gain = granuleChannel.globalGain() - GAIN_OFFSET - midSideGain(header);
        final var shift = granuleChannel.scalefacScale() + 1;
        final var longEnd = longBandsEnd(header, granuleChannel.windowing().layout());
        for (var band = 0; band < longEnd; band++) {
            final var factor =
                    scaleFactors.longFactor(band) + granuleChannel.preflag() * ScaleFactorTables.pretab(band);
            fillExponent(bands.longBandStart(band), bands.longBandWidth(band), gain - (factor << shift));
        }
        switch (granuleChannel.windowing()) {
            case Windowing.Normal _ -> {}
            case Windowing.Switched switched -> {
                if (switched.layout() != BlockLayout.LONG) {
                    computeShortExponents(bands, switched, gain, shift, scaleFactors);
                }
            }
        }
    }

    // FFmpeg takes the mode extension as it is in the header, whatever the channel mode.
    private static int midSideGain(final FrameHeader header) {
        if ((header.modeExtension() & (MID_SIDE_BIT | INTENSITY_BIT)) == MID_SIDE_BIT) {
            return MID_SIDE_GAIN;
        }
        return 0;
    }

    // After its long bands, a short or mixed block's short bands each hold three windows, one after another.
    private void computeShortExponents(
            final ScaleFactorBands bands,
            final Windowing.Switched switched,
            final int gain,
            final int shift,
            final ScaleFactors scaleFactors) {
        final var firstBand = firstShortBand(switched.layout());
        var line = 3 * bands.shortBandStart(firstBand);
        for (var band = firstBand; band < ScaleFactorBands.SHORT_BANDS; band++) {
            final var width = bands.shortBandWidth(band);
            for (var window = 0; window < Windowing.WINDOWS; window++) {
                final var windowGain = gain - SUBBLOCK_GAIN_STEP * switched.subblockGain(window);
                fillExponent(line, width, windowGain - (scaleFactors.shortFactor(band, window) << shift));
                line += width;
            }
        }
    }

    private void fillExponent(final int start, final int width, final int exponent) {
        Arrays.fill(exponents, start, start + width, exponent + EXPONENT_OFFSET);
    }

    private static int longBandsEnd(final FrameHeader header, final BlockLayout layout) {
        return switch (layout) {
            case LONG -> ScaleFactorBands.LONG_BANDS;
            case SHORT -> 0;
            case MIXED -> header.version().mixedLongBands();
        };
    }

    private static int firstShortBand(final BlockLayout layout) {
        return switch (layout) {
            case LONG, SHORT -> 0;
            case MIXED -> ScaleFactors.MIXED_FIRST_SHORT_BAND;
        };
    }

    /**
     * Reads the big values, which come in up to three regions, each coded with its own table, while the channel has
     * bits left.
     *
     * @param available how many bits the channel has for its values
     * @return how many bits they take
     */
    private int readBigValues(
            final BitInputStream bits,
            final FrameHeader header,
            final GranuleChannel granuleChannel,
            final int end,
            final int available)
            throws IOException {
        final var bands = ScaleFactorBands.fromSampleRate(header.sampleRate());
        return switch (granuleChannel.windowing()) {
            case Windowing.Normal normal -> readNormalRegions(bits, bands, normal, end, available);
            case Windowing.Switched switched -> readSwitchedRegions(bits, bands, switched, end, available);
        };
    }

    // The regions end at the long bands the side information gives.
    private int readNormalRegions(
            final BitInputStream bits,
            final ScaleFactorBands bands,
            final Windowing.Normal normal,
            final int end,
            final int available)
            throws IOException {
        final var region1 = Math.min(bands.longBandStart(normal.region0Count() + 1), end);
        final var region2Band =
                Math.min(normal.region0Count() + normal.region1Count() + 2, ScaleFactorBands.LONG_BANDS);
        final var region2 = Math.max(Math.min(bands.longBandStart(region2Band), end), region1);
        final var region0Bits = readRegion(bits, HuffmanTables.bigValueTable(normal.table0()), 0, region1, available);
        final var region1Bits = readRegion(
                bits, HuffmanTables.bigValueTable(normal.table1()), region1, region2, available - region0Bits);
        return region0Bits
                + region1Bits
                + readRegion(
                        bits,
                        HuffmanTables.bigValueTable(normal.table2()),
                        region2,
                        end,
                        available - region0Bits - region1Bits);
    }

    // The first region takes 8 long bands, or 3 short bands of three windows, and the second takes the rest.
    private int readSwitchedRegions(
            final BitInputStream bits,
            final ScaleFactorBands bands,
            final Windowing.Switched switched,
            final int end,
            final int available)
            throws IOException {
        final var region1Start = switch (switched.blockType()) {
            case SHORT -> 3 * bands.shortBandStart(3);
            case NORMAL, START, STOP -> bands.longBandStart(8);
        };
        final var region1 = Math.min(region1Start, end);
        final var region0Bits = readRegion(bits, HuffmanTables.bigValueTable(switched.table0()), 0, region1, available);
        return region0Bits
                + readRegion(
                        bits, HuffmanTables.bigValueTable(switched.table1()), region1, end, available - region0Bits);
    }

    // Each codeword codes a pair of values, x and y. A pair is read whole when it starts before the bits run out.
    private int readRegion(
            final BitInputStream bits, final BigValueTable table, final int start, final int end, final int available)
            throws IOException {
        var bitsRead = 0;
        for (var line = start; line < end && bitsRead < available; line += 2) {
            final var pair = table.code().readValue(bits);
            bitsRead += table.code().codeLength(pair)
                    + readValue(bits, table, pair / table.size(), line, exponents[line])
                    + readValue(bits, table, pair % table.size(), line + 1, exponents[line]);
        }
        return bitsRead;
    }

    // A value of 15 in a table with linbits is followed by that many bits to add to it, and a value that isn't 0 by its
    // sign. Returns how many bits those take. FFmpeg requantizes both values of a pair with the first line's exponent,
    // and values of 15 or more in whole numbers.
    private int readValue(
            final BitInputStream bits,
            final BigValueTable table,
            final int magnitude,
            final int line,
            final int exponent)
            throws IOException {
        if (magnitude == 0) {
            lines[line] = 0;
            return 0;
        }
        if (magnitude < LINBITS_VALUE) {
            lines[line] = signed(bits, FfmpegTables.requantized(exponent, magnitude));
            return 1;
        }
        final var linbits = table.linbits();
        var value = magnitude;
        if (linbits > 0) {
            value += (int) bits.readBits(linbits);
        }
        lines[line] = signed(bits, FfmpegTables.requantizedLarge(value, exponent));
        return linbits + 1;
    }

    // Count1 values come four at a time, each 0 or 1 with a sign bit for each 1, until the channel's bits run out. Some
    // encoders write a last quadruple that runs past them, which decoders drop, so it's left unread. FFmpeg notices
    // that only when it goes on to another quadruple, so a quadruple after which no other fits in the granule is kept.
    private int readCount1(
            final BitInputStream bits,
            final HuffmanCode code,
            final int start,
            final int available,
            final int presentAvailable)
            throws IOException {
        var line = start;
        var left = available;
        var presentLeft = presentAvailable;
        while (line + QUAD <= LINES && left > 0 && presentLeft > 0) {
            final var quad = code.peekValue(bits);
            final var quadBits = code.codeLength(quad) + Integer.bitCount(quad);
            if (quadBits > left && line + 2 * QUAD <= LINES) {
                break;
            }
            code.readValue(bits);
            for (var value = 0; value < QUAD; value++) {
                lines[line + value] = readCount1Value(bits, quad >> (QUAD - 1 - value) & 1, line + value);
            }
            left -= quadBits;
            presentLeft -= quadBits;
            line += QUAD;
        }
        return line;
    }

    private float readCount1Value(final BitInputStream bits, final int magnitude, final int line) throws IOException {
        if (magnitude == 0) {
            return 0;
        }
        return signed(bits, FfmpegTables.requantizedOne(exponents[line]));
    }

    // A sign bit of 1 is negative.
    private static float signed(final BitInputStream bits, final float value) throws IOException {
        if (bits.readBits(1) == 1) {
            return -value;
        }
        return value;
    }
}
