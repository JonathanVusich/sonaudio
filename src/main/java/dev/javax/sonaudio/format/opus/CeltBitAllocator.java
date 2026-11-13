package dev.javax.sonaudio.format.opus;

import java.util.Arrays;

/**
 * The bit allocation of a CELT frame (RFC 6716 section 4.3.3): how many eighths of a bit each band gets, which it
 * splits between fine energy and pulses, which bands are skipped, and the stereo parameters coded with it. It reads
 * the frame's spread decision, band boosts, allocation trim, skip markers, intensity band and dual stereo flag from
 * the range decoder, in that order. FFmpeg's {@code ff_celt_bitalloc} is the same computation.
 *
 * <p>What it works out per band stays in it until the next frame is allocated, as the energy and shape decoding of
 * the frame ask for it band by band.
 */
final class CeltBitAllocator {

    private static final int BAND_COUNT = CeltTables.BAND_COUNT;
    private static final int VECTORS = 11;
    private static final int ALLOCATION_STEPS = 6;
    private static final int FINE_OFFSET = 21;
    private static final int MAX_FINE_BITS = 8;
    private static final int EIGHTH = 8;
    private static final int INITIAL_BOOST_COST = 6;
    private static final int DEFAULT_TRIM = 5;

    private final int[] caps = new int[BAND_COUNT];
    private final int[] boosts = new int[BAND_COUNT];
    private final int[] trimOffsets = new int[BAND_COUNT];
    private final int[] thresholds = new int[BAND_COUNT];
    private final int[] lowerBits = new int[BAND_COUNT];
    private final int[] bitsRise = new int[BAND_COUNT];
    private final int[] pulses = new int[BAND_COUNT];
    private final int[] fineBits = new int[BAND_COUNT];
    private final int[] finePriorities = new int[BAND_COUNT];

    // The bits that are not yet reserved, in eighths of a bit, and the reservations, which the steps of one frame's
    // allocation share.
    private int eighthsLeft;
    private int skipReserve;
    private int intensityReserve;
    private int dualReserve;
    private int skipStartBand;
    private int totalBits;

    /**
     * Allocates the bits of a frame.
     *
     * @param rangeDecoder the frame's range decoder, positioned after the tf changes
     * @param frame the frame
     * @return what the allocation decides for the frame as a whole
     */
    CeltAllocation allocate(final RangeDecoder rangeDecoder, final CeltFrame frame) {
        final var spread = decodeSpread(rangeDecoder, frame);
        computeCaps(frame);
        decodeBoosts(rangeDecoder, frame);
        final var trim = decodeTrim(rangeDecoder, frame);
        final var anticollapseReserve = reserveBits(rangeDecoder, frame);
        computeTrimOffsetsAndThresholds(frame, trim);
        final var vectorHigh = findStaticAllocation(frame);
        final var interpolation = interpolateAllocations(frame, vectorHigh);
        applyInterpolation(frame, interpolation);
        final var codedBands = skipBands(rangeDecoder, frame);
        final var intensityBand = decodeIntensityBand(rangeDecoder, frame, codedBands);
        final var dualStereo = decodeDualStereo(rangeDecoder, frame, intensityBand);
        final var remaining = distributeBits(frame, codedBands, intensityBand, dualStereo);
        return new CeltAllocation(spread, codedBands, intensityBand, dualStereo, anticollapseReserve, remaining);
    }

    /**
     * @param band a band
     * @return the eighths of a bit its pulses get, once the allocation is done
     */
    int pulses(final int band) {
        return pulses[band];
    }

    /**
     * @param band a band
     * @return how many bits of fine energy it codes per channel
     */
    int fineBits(final int band) {
        return fineBits[band];
    }

    /**
     * @param band a band
     * @return 0 if it gets a bit of the final fine energy pass first, 1 if it gets it second
     */
    int finePriority(final int band) {
        return finePriorities[band];
    }

    private CeltSpread decodeSpread(final RangeDecoder rangeDecoder, final CeltFrame frame) {
        if (rangeDecoder.tell() + 4 > frame.frameBits()) {
            return CeltSpread.NORMAL;
        }
        final var symbol = rangeDecoder.decodeIcdf(CeltTables.SPREAD_ICDF, CeltTables.SPREAD_BITS);
        return CeltSpread.values()[symbol];
    }

    // FFmpeg's NORMC: eighths of a bit per band for the frame's channels and size, from a per-sample figure.
    private static int scaleToFrame(final CeltFrame frame, final int bits) {
        return bits << (frame.channels() - 1) << frame.sizeShift() >> 2;
    }

    private void computeCaps(final CeltFrame frame) {
        final var staticCaps = CeltTables.STATIC_CAPS[frame.sizeShift()][frame.channels() - 1];
        for (var band = 0; band < BAND_COUNT; band++) {
            caps[band] = scaleToFrame(frame, (staticCaps[band] + 64) * CeltTables.BAND_WIDTH[band]);
        }
    }

    private void decodeBoosts(final RangeDecoder rangeDecoder, final CeltFrame frame) {
        Arrays.fill(boosts, 0);
        eighthsLeft = frame.frameBits() << 3;
        var dynamicCost = INITIAL_BOOST_COST;
        for (var band = frame.startBand(); band < frame.endBand(); band++) {
            decodeBandBoost(rangeDecoder, frame, band, dynamicCost);
            if (boosts[band] != 0) {
                dynamicCost = Math.max(dynamicCost - 1, 2);
            }
        }
    }

    private void decodeBandBoost(
            final RangeDecoder rangeDecoder, final CeltFrame frame, final int band, final int dynamicCost) {
        final var bandBits = (CeltTables.BAND_WIDTH[band] << (frame.channels() - 1)) << frame.sizeShift();
        final var quanta = Math.min(bandBits << 3, Math.max(6 << 3, bandBits));
        var cost = dynamicCost;
        while (rangeDecoder.tellFraction() + (cost << 3) < eighthsLeft && boosts[band] < caps[band]) {
            if (!rangeDecoder.decodeBit(cost)) {
                break;
            }
            boosts[band] += quanta;
            eighthsLeft -= quanta;
            cost = 1;
        }
    }

    private int decodeTrim(final RangeDecoder rangeDecoder, final CeltFrame frame) {
        if (rangeDecoder.tellFraction() + (6 << 3) <= eighthsLeft) {
            return rangeDecoder.decodeIcdf(CeltTables.ALLOCATION_TRIM_ICDF, CeltTables.ALLOCATION_TRIM_BITS);
        }
        return DEFAULT_TRIM;
    }

    // Takes the bits that the anti-collapse bit, the skip markers and the stereo parameters will need out of what is
    // left. Returns what is kept for the anti-collapse bit.
    private int reserveBits(final RangeDecoder rangeDecoder, final CeltFrame frame) {
        eighthsLeft = (frame.frameBits() << 3) - rangeDecoder.tellFraction() - 1;
        var anticollapseReserve = 0;
        if (frame.blockMode() == BlockMode.SHORT && frame.sizeShift() >= 2 && eighthsLeft >= ((frame.sizeShift() + 2) << 3)) {
            anticollapseReserve = EIGHTH;
        }
        eighthsLeft -= anticollapseReserve;
        skipReserve = 0;
        if (eighthsLeft >= EIGHTH) {
            skipReserve = EIGHTH;
        }
        eighthsLeft -= skipReserve;
        intensityReserve = 0;
        dualReserve = 0;
        if (frame.channels() == 2) {
            reserveStereoBits(frame);
        }
        return anticollapseReserve;
    }

    private void reserveStereoBits(final CeltFrame frame) {
        final var intensityBits = CeltTables.LOG2_FRAC[frame.endBand() - frame.startBand()];
        if (intensityBits > eighthsLeft) {
            return;
        }
        intensityReserve = intensityBits;
        eighthsLeft -= intensityBits;
        if (eighthsLeft >= EIGHTH) {
            dualReserve = EIGHTH;
            eighthsLeft -= EIGHTH;
        }
    }

    private void computeTrimOffsetsAndThresholds(final CeltFrame frame, final int trim) {
        final var sizeShift = frame.sizeShift();
        final var channels = frame.channels();
        for (var band = frame.startBand(); band < frame.endBand(); band++) {
            final var width = CeltTables.BAND_WIDTH[band];
            final var duration = sizeShift + 3;
            final var scale = duration + channels - 1;
            final var bandsAbove = width * (frame.endBand() - band - 1);
            thresholds[band] = Math.max(3 * width << duration >> 4, channels << 3);
            trimOffsets[band] = (trim - 5 - sizeShift) * (bandsAbove << scale) >> 6;
            if (width << sizeShift == 1) {
                trimOffsets[band] -= channels << 3;
            }
        }
    }

    // Bisects between the static allocation vectors for the first one that costs more than the bits that are left,
    // and returns it: the vector below it is the lower end of the interpolation.
    private int findStaticAllocation(final CeltFrame frame) {
        var low = 1;
        var high = VECTORS - 1;
        while (low <= high) {
            final var center = (low + high) >> 1;
            if (staticAllocationCost(frame, center) > eighthsLeft) {
                high = center - 1;
            } else {
                low = center + 1;
            }
        }
        return low;
    }

    private int staticAllocationCost(final CeltFrame frame, final int vector) {
        var done = false;
        var total = 0;
        for (var band = frame.endBand() - 1; band >= frame.startBand(); band--) {
            var bandBits = scaleToFrame(frame, CeltTables.BAND_WIDTH[band] * CeltTables.STATIC_ALLOC[vector][band]);
            if (bandBits != 0) {
                bandBits = Math.max(bandBits + trimOffsets[band], 0);
            }
            bandBits += boosts[band];
            if (bandBits >= thresholds[band] || done) {
                done = true;
                total += Math.min(bandBits, caps[band]);
            } else if (bandBits >= frame.channels() << 3) {
                total += frame.channels() << 3;
            }
        }
        return total;
    }

    // Sets the bits at the vector below the one found, and how much they rise to the vector above it, for each band.
    private int interpolateAllocations(final CeltFrame frame, final int vectorAbove) {
        final var vectorBelow = vectorAbove - 1;
        skipStartBand = frame.startBand();
        for (var band = frame.startBand(); band < frame.endBand(); band++) {
            lowerBits[band] = trimmedStaticBits(frame, vectorBelow, band);
            var upperBits = trimmedStaticBits(frame, vectorAbove, band);
            if (vectorBelow != 0) {
                lowerBits[band] += boosts[band];
            }
            upperBits += boosts[band];
            if (boosts[band] != 0) {
                skipStartBand = band;
            }
            bitsRise[band] = Math.max(upperBits - lowerBits[band], 0);
        }
        return vectorBelow;
    }

    // The bits of a band at a static allocation vector, which is the band's cap for the vector above the last, after the
    // trim, as FFmpeg trims the cap too.
    private int trimmedStaticBits(final CeltFrame frame, final int vector, final int band) {
        var bandBits = caps[band];
        if (vector < VECTORS) {
            bandBits = scaleToFrame(frame, CeltTables.BAND_WIDTH[band] * CeltTables.STATIC_ALLOC[vector][band]);
        }
        if (bandBits == 0) {
            return 0;
        }
        return Math.max(bandBits + trimOffsets[band], 0);
    }

    // Bisects six times for how far between the two vectors the allocation goes, in sixty-fourths, and rounds the
    // bands' bits at that point into pulses.
    private void applyInterpolation(final CeltFrame frame, final int vectorBelow) {
        var low = 0;
        var high = 1 << ALLOCATION_STEPS;
        for (var step = 0; step < ALLOCATION_STEPS; step++) {
            final var center = (low + high) >> 1;
            if (interpolatedCost(frame, center) > eighthsLeft) {
                high = center;
            } else {
                low = center;
            }
        }
        totalBits = 0;
        var done = false;
        for (var band = frame.endBand() - 1; band >= frame.startBand(); band--) {
            var bandBits = lowerBits[band] + (low * bitsRise[band] >> ALLOCATION_STEPS);
            if (bandBits >= thresholds[band] || done) {
                done = true;
            } else if (bandBits >= frame.channels() << 3) {
                bandBits = frame.channels() << 3;
            } else {
                bandBits = 0;
            }
            bandBits = Math.min(bandBits, caps[band]);
            pulses[band] = bandBits;
            totalBits += bandBits;
        }
    }

    private int interpolatedCost(final CeltFrame frame, final int fraction) {
        var done = false;
        var total = 0;
        for (var band = frame.endBand() - 1; band >= frame.startBand(); band--) {
            final var bandBits = lowerBits[band] + (fraction * bitsRise[band] >> ALLOCATION_STEPS);
            if (bandBits >= thresholds[band] || done) {
                done = true;
                total += Math.min(bandBits, caps[band]);
            } else if (bandBits >= frame.channels() << 3) {
                total += frame.channels() << 3;
            }
        }
        return total;
    }

    // Walks down from the top band, reading a marker for each band that could be skipped, until a band is kept.
    private int skipBands(final RangeDecoder rangeDecoder, final CeltFrame frame) {
        var codedBands = frame.endBand();
        while (true) {
            final var topBand = codedBands - 1;
            if (topBand == skipStartBand) {
                eighthsLeft += skipReserve;
                return codedBands;
            }
            if (isBandKept(rangeDecoder, frame, topBand)) {
                return codedBands;
            }
            codedBands--;
        }
    }

    // Reads the skip marker of a band that has enough bits to be worth one, and reclaims the bits of one that is
    // skipped. Returns whether the band is kept.
    private boolean isBandKept(final RangeDecoder rangeDecoder, final CeltFrame frame, final int band) {
        final var channels = frame.channels();
        final var bandsBelow = CeltTables.BAND_START[band] - CeltTables.BAND_START[frame.startBand()];
        final var bandsUpTo = CeltTables.BAND_START[band + 1] - CeltTables.BAND_START[frame.startBand()];
        var remaining = eighthsLeft - totalBits;
        final var share = remaining / bandsUpTo;
        remaining -= share * bandsUpTo;
        var allocation = pulses[band] + share * CeltTables.BAND_WIDTH[band];
        allocation += Math.max(remaining - bandsBelow, 0);
        if (allocation >= Math.max(thresholds[band], (channels + 1) << 3)) {
            if (rangeDecoder.decodeBit(1)) {
                return true;
            }
            totalBits += EIGHTH;
            allocation -= EIGHTH;
        }
        reclaimSkippedBand(frame, band, allocation);
        return false;
    }

    private void reclaimSkippedBand(final CeltFrame frame, final int band, final int allocation) {
        totalBits -= pulses[band];
        if (intensityReserve != 0) {
            totalBits -= intensityReserve;
            intensityReserve = CeltTables.LOG2_FRAC[band - frame.startBand()];
            totalBits += intensityReserve;
        }
        var kept = 0;
        if (allocation >= frame.channels() << 3) {
            kept = frame.channels() << 3;
        }
        pulses[band] = kept;
        totalBits += kept;
    }

    private int decodeIntensityBand(final RangeDecoder rangeDecoder, final CeltFrame frame, final int codedBands) {
        if (intensityReserve == 0) {
            return 0;
        }
        return frame.startBand() + rangeDecoder.decodeUniform(codedBands + 1 - frame.startBand());
    }

    private CeltStereoMode decodeDualStereo(
            final RangeDecoder rangeDecoder, final CeltFrame frame, final int intensityBand) {
        if (intensityBand <= frame.startBand()) {
            eighthsLeft += dualReserve;
            return CeltStereoMode.JOINT;
        }
        if (dualReserve != 0 && rangeDecoder.decodeBit(1)) {
            return CeltStereoMode.DUAL;
        }
        return CeltStereoMode.JOINT;
    }

    // Gives the bits that are left to the coded bands, then splits each band's bits between fine energy and pulses.
    // Returns the bits that no band could take.
    private int distributeBits(
            final CeltFrame frame, final int codedBands, final int intensityBand, final CeltStereoMode dualStereo) {
        final var span = CeltTables.BAND_START[codedBands] - CeltTables.BAND_START[frame.startBand()];
        var remaining = eighthsLeft - totalBits;
        final var share = remaining / span;
        remaining -= share * span;
        for (var band = frame.startBand(); band < codedBands; band++) {
            final var extra = Math.min(remaining, CeltTables.BAND_WIDTH[band]);
            pulses[band] += extra + share * CeltTables.BAND_WIDTH[band];
            remaining -= extra;
        }
        var extraBits = 0;
        for (var band = frame.startBand(); band < codedBands; band++) {
            extraBits = splitBand(frame, band, extraBits, intensityBand, dualStereo);
        }
        for (var band = codedBands; band < frame.endBand(); band++) {
            fineBits[band] = pulses[band] >> (frame.channels() - 1) >> 3;
            pulses[band] = 0;
            finePriorities[band] = 0;
            if (fineBits[band] < 1) {
                finePriorities[band] = 1;
            }
        }
        return extraBits;
    }

    // Splits one band's bits and returns the bits it hands on to the next band.
    private int splitBand(
            final CeltFrame frame,
            final int band,
            final int previousExtra,
            final int intensityBand,
            final CeltStereoMode dualStereo) {
        final var size = frame.bandSize(band);
        pulses[band] += previousExtra;
        var extra = 0;
        if (size > 1) {
            extra = Math.max(pulses[band] - caps[band], 0);
            pulses[band] -= extra;
            chooseFineBits(frame, band, intensityBand, dualStereo);
        } else {
            extra = Math.max(pulses[band] - (frame.channels() << 3), 0);
            pulses[band] -= extra;
            fineBits[band] = 0;
            finePriorities[band] = 1;
        }
        if (extra > 0) {
            extra = returnExtraToFineBits(frame, band, extra, previousExtra);
        }
        return extra;
    }

    private void chooseFineBits(
            final CeltFrame frame, final int band, final int intensityBand, final CeltStereoMode dualStereo) {
        final var channels = frame.channels();
        final var size = frame.bandSize(band);
        var degrees = size * channels;
        if (channels == 2 && size > 2 && dualStereo == CeltStereoMode.JOINT && band < intensityBand) {
            degrees++;
        }
        final var temp = degrees * (CeltTables.LOG_BAND_WIDTH[band] + (frame.sizeShift() << 3));
        var offset = (temp >> 1) - degrees * FINE_OFFSET;
        if (size == 2) {
            offset += degrees << 1;
        }
        if (pulses[band] + offset < 2 * (degrees << 3)) {
            offset += temp >> 2;
        } else if (pulses[band] + offset < 3 * (degrees << 3)) {
            offset += temp >> 3;
        }
        final var wanted = (pulses[band] + offset + (degrees << 2)) / (degrees << 3);
        final var limit = Math.max(Math.min((pulses[band] >> 3) >> (channels - 1), MAX_FINE_BITS), 0);
        fineBits[band] = Math.max(0, Math.min(wanted, limit));
        finePriorities[band] = 0;
        if (fineBits[band] * (degrees << 3) >= pulses[band] + offset) {
            finePriorities[band] = 1;
        }
        pulses[band] -= fineBits[band] << (channels - 1) << 3;
    }

    private int returnExtraToFineBits(final CeltFrame frame, final int band, final int extra, final int previousExtra) {
        final var channels = frame.channels();
        var fineExtra = Math.min(extra >> (channels + 2), MAX_FINE_BITS - fineBits[band]);
        fineBits[band] += fineExtra;
        fineExtra <<= channels + 2;
        finePriorities[band] = 0;
        if (fineExtra >= extra - previousExtra) {
            finePriorities[band] = 1;
        }
        return extra - fineExtra;
    }
}
