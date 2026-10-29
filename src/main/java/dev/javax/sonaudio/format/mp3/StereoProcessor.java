package dev.javax.sonaudio.format.mp3;

/**
 * Turns a joint stereo granule's coded channels back into left and right (ISO/IEC 11172-3 section 2.4.3.4.9.2,
 * ISO/IEC 13818-3 section 2.4.3.2). Mid/side stereo codes the channels' sum and difference. Intensity stereo codes only
 * the left channel above the right channel's last line that isn't 0, and each band's intensity position, the right
 * channel's scale factor there, says how to split it between them. Both work on the lines in the order they're coded,
 * before short bands are reordered.
 */
final class StereoProcessor {

    private static final float INVERSE_SQRT_2 = 0.70710678118654752440f;
    // The bands above the scale factor bands use the intensity position of the band below them.
    private static final int LAST_LONG_POSITION_BAND = ScaleFactorBands.LONG_BANDS - 2;
    private static final int LAST_SHORT_POSITION_BAND = ScaleFactorBands.SHORT_BANDS - 2;

    /**
     * @param rightChannel the right channel's side information, whose scale factors give the intensity positions
     */
    void processStereo(
            final FrameHeader header,
            final Spectrum left,
            final Spectrum right,
            final GranuleChannel rightChannel,
            final ScaleFactors rightFactors) {
        final var end = Math.max(left.nonZeroEnd(), right.nonZeroEnd());
        if (header.isIntensityStereo()) {
            processIntensityStereo(header, left, right, rightChannel, rightFactors);
            left.setNonZeroEnd(Spectrum.LINES);
            right.setNonZeroEnd(Spectrum.LINES);
        } else if (header.isMidSideStereo()) {
            sumAndDifference(left, right, 0, end);
            left.setNonZeroEnd(end);
            right.setNonZeroEnd(end);
        }
    }

    private void processIntensityStereo(
            final FrameHeader header,
            final Spectrum left,
            final Spectrum right,
            final GranuleChannel rightChannel,
            final ScaleFactors rightFactors) {
        final var bands = ScaleFactorBands.fromSampleRate(header.sampleRate());
        final var layout = rightChannel.windowing().layout();
        final var longBands = switch (layout) {
            case LONG -> ScaleFactorBands.LONG_BANDS;
            case SHORT -> 0;
            case MIXED -> header.version().mixedLongBands();
        };
        var shortBandsCoded = false;
        if (layout != BlockLayout.LONG) {
            shortBandsCoded = processShortBands(header, bands, left, right, rightChannel, rightFactors);
        }
        var firstIntensityBand = longBands;
        if (!shortBandsCoded) {
            firstIntensityBand = firstIntensityLongBand(bands, right, longBands);
        }
        for (var band = 0; band < longBands; band++) {
            final var positionBand = Math.min(band, LAST_LONG_POSITION_BAND);
            processBand(
                    header,
                    left,
                    right,
                    rightChannel,
                    bands.longBandStart(band),
                    bands.longBandWidth(band),
                    rightPart(band, firstIntensityBand),
                    rightFactors.longFactor(positionBand));
        }
    }

    // Each window's intensity coding starts above its own last band that isn't 0.
    private boolean processShortBands(
            final FrameHeader header,
            final ScaleFactorBands bands,
            final Spectrum left,
            final Spectrum right,
            final GranuleChannel rightChannel,
            final ScaleFactors rightFactors) {
        final var firstBand = firstShortBand(rightChannel.windowing().layout());
        var coded = false;
        for (var window = 0; window < Windowing.WINDOWS; window++) {
            final var firstIntensityBand = firstIntensityShortBand(bands, right, firstBand, window);
            coded |= firstIntensityBand > firstBand;
            for (var band = firstBand; band < ScaleFactorBands.SHORT_BANDS; band++) {
                final var positionBand = Math.min(band, LAST_SHORT_POSITION_BAND);
                processBand(
                        header,
                        left,
                        right,
                        rightChannel,
                        shortLineStart(bands, band, window),
                        bands.shortBandWidth(band),
                        rightPart(band, firstIntensityBand),
                        rightFactors.shortFactor(positionBand, window));
            }
        }
        return coded;
    }

    // A band above the right channel's last coded one is intensity coded, unless its position is the illegal one.
    // Others are mid/side coded where the frame says so, and left as they are otherwise.
    private void processBand(
            final FrameHeader header,
            final Spectrum left,
            final Spectrum right,
            final GranuleChannel rightChannel,
            final int start,
            final int width,
            final RightPart rightPart,
            final int position) {
        if (rightPart == RightPart.ZERO && position < IntensityRatios.positionLimit(header.version())) {
            final var compress = rightChannel.scalefacCompress();
            final var leftRatio = IntensityRatios.leftRatio(header.version(), compress, position);
            final var rightRatio = IntensityRatios.rightRatio(header.version(), compress, position);
            for (var line = start; line < start + width; line++) {
                final var value = left.line(line);
                left.setLine(line, value * leftRatio);
                right.setLine(line, value * rightRatio);
            }
        } else if (header.isMidSideStereo()) {
            midSide(left, right, start, start + width);
        }
    }

    // FFmpeg folds the 1/sqrt(2) of mid/side stereo into the global gain when no band is intensity coded, as the
    // gain's steps are 2^(1/4), and doesn't scale the sum and difference then.
    private static void sumAndDifference(final Spectrum left, final Spectrum right, final int start, final int end) {
        for (var line = start; line < end; line++) {
            final var mid = left.line(line);
            final var side = right.line(line);
            left.setLine(line, mid + side);
            right.setLine(line, mid - side);
        }
    }

    private static void midSide(final Spectrum left, final Spectrum right, final int start, final int end) {
        for (var line = start; line < end; line++) {
            final var mid = left.line(line);
            final var side = right.line(line);
            left.setLine(line, (mid + side) * INVERSE_SQRT_2);
            right.setLine(line, (mid - side) * INVERSE_SQRT_2);
        }
    }

    private static RightPart rightPart(final int band, final int firstIntensityBand) {
        if (band >= firstIntensityBand) {
            return RightPart.ZERO;
        }
        return RightPart.CODED;
    }

    // The band above the last long one with a value, which is where intensity coding starts, or 0 when none has.
    private static int firstIntensityLongBand(final ScaleFactorBands bands, final Spectrum right, final int longBands) {
        for (var band = longBands - 1; band >= 0; band--) {
            if (hasValue(right, bands.longBandStart(band), bands.longBandWidth(band))) {
                return band + 1;
            }
        }
        return 0;
    }

    // The same for a window's short bands, or the first short band when none has a value.
    private static int firstIntensityShortBand(
            final ScaleFactorBands bands, final Spectrum right, final int firstBand, final int window) {
        for (var band = ScaleFactorBands.SHORT_BANDS - 1; band >= firstBand; band--) {
            if (hasValue(right, shortLineStart(bands, band, window), bands.shortBandWidth(band))) {
                return band + 1;
            }
        }
        return firstBand;
    }

    private static boolean hasValue(final Spectrum spectrum, final int start, final int width) {
        for (var line = start; line < start + width; line++) {
            if (spectrum.line(line) != 0) {
                return true;
            }
        }
        return false;
    }

    // Before reordering, each short band holds its windows one after another.
    private static int shortLineStart(final ScaleFactorBands bands, final int band, final int window) {
        return Windowing.WINDOWS * bands.shortBandStart(band) + window * bands.shortBandWidth(band);
    }

    private static int firstShortBand(final BlockLayout layout) {
        return switch (layout) {
            case LONG, SHORT -> 0;
            case MIXED -> ScaleFactors.MIXED_FIRST_SHORT_BAND;
        };
    }

    // Whether a band is in the right channel's coded part, or above it, where the right channel is all 0.
    private enum RightPart {
        CODED,
        ZERO
    }
}
