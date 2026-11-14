package dev.javax.sonaudio.format.opus;

import dev.javax.sonaudio.format.opus.CeltVectorUtils.BlockOrder;
import java.util.Arrays;
import lombok.Getter;
import lombok.Setter;

/**
 * Decodes the shape of every band of a CELT frame (RFC 6716 section 4.3.4): the normalised coefficients of each
 * channel, from the pulses, the angles that split stereo and wide bands, and the folding of lower bands into those
 * that have no pulses. FFmpeg's {@code ff_celt_quant_bands} and {@code quant_band_template} are the same decoding.
 *
 * <p>Bands are decoded recursively: a band is split in two when its bits could code more pulses than one vector can
 * have, and a stereo band is split in mid and side. Each level of that recursion has a {@link BandState} of its own,
 * made when the decoder is built, so decoding allocates nothing.
 */
final class CeltBandDecoder {

    private static final int MAX_BAND_COEFFICIENTS = 8 * 22;
    private static final int MAX_FOLDED_COEFFICIENTS = 8 * 100;
    private static final int MAX_DEPTH = 16;
    private static final int MAX_BAND_BITS = 14;
    private static final int FULL_ANGLE = 16384;
    private static final int HALF_ANGLE = 8192;
    private static final int SIGN_EIGHTHS = 8;
    private static final int NOISE_BIT = 0x8000;
    private static final float FOLD_NOISE_LEVEL = 1.0f / 256;

    /**
     * Whether the band that folding starts from may move up to the band just decoded: it may when that band had more
     * than a bit for each coefficient.
     */
    private enum FoldUpdate {
        ALLOWED,
        FROZEN
    }

    /**
     * Whether a band has a lower band to fold from.
     */
    private enum Fold {
        ABSENT,
        AVAILABLE
    }

    private final CeltNoise noise;
    private final float[][] coefficients = new float[2][CeltWindowTables.LENGTH * 8];
    private final int[][] collapseMasks = new int[2][CeltTables.BAND_COUNT];
    private final float[][] folded = new float[2][MAX_FOLDED_COEFFICIENTS];
    private final float[] foldScratch = new float[MAX_BAND_COEFFICIENTS];
    private final float[] hadamardScratch = new float[MAX_BAND_COEFFICIENTS];
    private final int[] pulseVector = new int[MAX_BAND_COEFFICIENTS];
    private final CeltPulseUtils.PulseCursor pulseCursor = new CeltPulseUtils.PulseCursor();
    private final BandState[] states = new BandState[MAX_DEPTH];

    // What one frame's decoding shares between the levels of the recursion.
    private RangeDecoder rangeDecoder;
    private CeltFrame frame;
    private CeltSpread spread;
    private int intensityBand;
    private int[] timeFrequencyChanges;
    private int eighthsLeft;
    private Fold fold;
    private int foldBin;
    private int foldFirstMask;
    private int foldSecondMask;

    /**
     * @param noise the generator that fills bands without pulses, which the frame's anti-collapse uses as well
     */
    CeltBandDecoder(final CeltNoise noise) {
        this.noise = noise;
        for (var depth = 0; depth < MAX_DEPTH; depth++) {
            states[depth] = new BandState();
        }
    }

    /**
     * @param channel 0 or 1
     * @return that channel's normalised coefficients of the last frame, grouped by band, which the next frame rewrites
     */
    float[] coefficients(final int channel) {
        return coefficients[channel];
    }

    /**
     * @param channel 0 or 1
     * @param band a band
     * @return a mask with a bit for each block of the band that has pulses or folded content
     */
    int collapseMask(final int channel, final int band) {
        return collapseMasks[channel][band];
    }

    /**
     * Decodes the bands of a frame.
     *
     * @param decoder the frame's range decoder, positioned after the fine energy
     * @param codedFrame the frame
     * @param allocation what the allocation decided for the frame
     * @param bits the allocation of each band
     * @param changes the frame's time-frequency change of each band
     */
    void decodeBands(
            final RangeDecoder decoder,
            final CeltFrame codedFrame,
            final CeltAllocation allocation,
            final CeltBitAllocator bits,
            final int[] changes) {
        beginFrame(decoder, codedFrame, allocation, changes);
        var dualStereo = allocation.dualStereo();
        var lowbandOffset = 0;
        var balance = allocation.remainingBits();
        var foldUpdate = FoldUpdate.ALLOWED;
        final var totalBits = (codedFrame.frameBits() << 3) - allocation.anticollapseReserve();
        for (var band = codedFrame.startBand(); band < codedFrame.endBand(); band++) {
            final var consumed = decoder.tellFraction();
            if (band != codedFrame.startBand()) {
                balance -= consumed;
            }
            eighthsLeft = totalBits - consumed - 1;
            final var bandBits = bandBits(allocation, bits, band, balance);
            lowbandOffset = nextLowbandOffset(band, lowbandOffset, foldUpdate);
            copyHybridFold(band);
            dualStereo = joinStereoAtIntensityBand(band, dualStereo);
            findFoldSource(band, lowbandOffset);
            decodeChannels(band, bandBits, dualStereo);
            balance += bits.pulses(band) + consumed;
            foldUpdate = foldUpdateAfter(band, bandBits);
        }
    }

    private void beginFrame(
            final RangeDecoder decoder,
            final CeltFrame codedFrame,
            final CeltAllocation allocation,
            final int[] changes) {
        rangeDecoder = decoder;
        frame = codedFrame;
        spread = allocation.spread();
        intensityBand = allocation.intensityBand();
        timeFrequencyChanges = changes;
        for (var channel = 0; channel < codedFrame.channels(); channel++) {
            Arrays.fill(coefficients[channel], 0, codedFrame.frameSamples(), 0f);
            Arrays.fill(collapseMasks[channel], 0);
        }
    }

    private int bandBits(
            final CeltAllocation allocation, final CeltBitAllocator bits, final int band, final int balance) {
        if (band > allocation.codedBands() - 1) {
            return 0;
        }
        final var share = balance / Math.min(3, allocation.codedBands() - band);
        final var wanted = Math.min(eighthsLeft + 1, bits.pulses(band) + share);
        return Math.clamp(wanted, 0, (1 << MAX_BAND_BITS) - 1);
    }

    // The band that folding starts from moves up while there is room to fold from below it.
    private int nextLowbandOffset(final int band, final int lowbandOffset, final FoldUpdate foldUpdate) {
        final var start = frame.startBand();
        final var roomBelow = CeltTables.BAND_START[band] - CeltTables.BAND_WIDTH[band] >= CeltTables.BAND_START[start];
        if ((roomBelow || band == start + 1) && (foldUpdate == FoldUpdate.ALLOWED || lowbandOffset == 0)) {
            return band;
        }
        return lowbandOffset;
    }

    // A hybrid frame has nothing below its first band to fold its second from, so it folds from the first.
    private void copyHybridFold(final int band) {
        if (band != frame.startBand() + 1) {
            return;
        }
        final var count = (CeltTables.BAND_WIDTH[band] - CeltTables.BAND_WIDTH[band - 1]) << frame.sizeShift();
        final var offset = frame.bandStart(band);
        for (var channel = 0; channel < frame.channels(); channel++) {
            System.arraycopy(folded[channel], offset - count, folded[channel], offset, count);
        }
    }

    // From the intensity band up, the channels are coded jointly, and what is folded is the average of the two.
    private CeltStereoMode joinStereoAtIntensityBand(final int band, final CeltStereoMode dualStereo) {
        if (dualStereo == CeltStereoMode.DUAL && band == intensityBand) {
            averageFoldedChannels(band);
            return CeltStereoMode.JOINT;
        }
        return dualStereo;
    }

    private void averageFoldedChannels(final int band) {
        for (var index = frame.bandStart(frame.startBand()); index < frame.bandStart(band); index++) {
            folded[0][index] = (folded[0][index] + folded[1][index]) / 2;
        }
    }

    private FoldUpdate foldUpdateAfter(final int band, final int bandBits) {
        if (bandBits > frame.bandSize(band) << 3) {
            return FoldUpdate.ALLOWED;
        }
        return FoldUpdate.FROZEN;
    }

    // Finds the lower band that a band folds from, and which of the blocks below it have content, so that the band's
    // blocks that have none are not folded as if they had.
    private void findFoldSource(final int band, final int lowbandOffset) {
        fold = Fold.ABSENT;
        final var covers = spread != CeltSpread.AGGRESSIVE || frame.blocks() > 1 || timeFrequencyChanges[band] < 0;
        if (lowbandOffset == 0 || !covers) {
            return;
        }
        fold = Fold.AVAILABLE;
        final var width = CeltTables.BAND_WIDTH[band];
        foldBin = Math.max(CeltTables.BAND_START[frame.startBand()], CeltTables.BAND_START[lowbandOffset] - width);
        collectFoldMasks(foldStartBand(lowbandOffset), foldEndBand(band, width, lowbandOffset));
    }

    private int foldStartBand(final int lowbandOffset) {
        var foldStart = lowbandOffset;
        do {
            foldStart--;
        } while (CeltTables.BAND_START[foldStart] > foldBin);
        return foldStart;
    }

    private int foldEndBand(final int band, final int width, final int lowbandOffset) {
        var foldEnd = lowbandOffset - 1;
        do {
            foldEnd++;
        } while (foldEnd < band && CeltTables.BAND_START[foldEnd] < foldBin + width);
        return foldEnd;
    }

    private void collectFoldMasks(final int foldStart, final int foldEnd) {
        foldFirstMask = 0;
        foldSecondMask = 0;
        for (var source = foldStart; source < foldEnd; source++) {
            foldFirstMask |= collapseMasks[0][source];
            foldSecondMask |= collapseMasks[frame.channels() - 1][source];
        }
    }

    private void decodeChannels(final int band, final int bandBits, final CeltStereoMode dualStereo) {
        final var firstFill = startingFill(foldFirstMask);
        final var secondFill = startingFill(foldSecondMask);
        if (dualStereo == CeltStereoMode.DUAL) {
            final var firstMask = decodeTopBand(band, 0, null, bandBits >> 1, firstFill);
            final var secondMask = decodeTopBand(band, 1, null, bandBits >> 1, secondFill);
            storeCollapseMasks(band, firstMask, secondMask);
            return;
        }
        final var mask = decodeTopBand(band, 0, secondChannel(), bandBits, firstFill | secondFill);
        storeCollapseMasks(band, mask, mask);
    }

    // The blocks to fill from the lower bands: those that have content, or all of them when there is nothing to fold.
    private int startingFill(final int foldMask) {
        if (fold == Fold.AVAILABLE) {
            return foldMask;
        }
        return (1 << frame.blocks()) - 1;
    }

    private float[] secondChannel() {
        if (frame.channels() == 1) {
            return null;
        }
        return coefficients[1];
    }

    private void storeCollapseMasks(final int band, final int firstMask, final int secondMask) {
        collapseMasks[0][band] = firstMask & 0xFF;
        collapseMasks[frame.channels() - 1][band] = secondMask & 0xFF;
    }

    // Decodes a band of one channel, or of a stereo pair when it is given the second channel's coefficients (null
    // for a single channel), folding from that channel's lower bands.
    private int decodeTopBand(final int band, final int channel, final float[] second, final int bits, final int fill) {
        final var offset = frame.bandStart(band);
        var lowband = folded[channel];
        if (fold == Fold.ABSENT) {
            lowband = null;
        }
        final var state = states[0];
        state.set(band, coefficients[channel], offset, second, offset, frame.bandSize(band), bits, frame.blocks());
        state.setLowband(
                lowband, foldBin << frame.sizeShift(), folded[channel], offset, frame.sizeShift(), 0, 1.0f, fill);
        return decodeBand(0);
    }

    private int decodeBand(final int depth) {
        final var state = states[depth];
        if (state.getSize() == 1) {
            return decodeSingleCoefficient(state);
        }
        state.begin();
        if (state.getCoding() == Coding.MONO && state.getLevel() == 0) {
            prepareTimeFrequency(state);
        }
        splitWideBand(state);
        var mask = 0;
        if (state.getY() != null) {
            mask = decodeSplitBand(depth, state);
        } else {
            mask = decodeWholeBand(state);
        }
        return finishBand(state, mask);
    }

    // A band of one coefficient has only a sign, per channel.
    private int decodeSingleCoefficient(final BandState state) {
        state.getX()[state.getXOffset()] = readSign();
        if (state.getY() != null) {
            state.getY()[state.getYOffset()] = readSign();
        }
        if (state.getLowbandOut() != null) {
            state.getLowbandOut()[state.getLowbandOutOffset()] = state.getX()[state.getXOffset()];
        }
        return 1;
    }

    private float readSign() {
        var sign = 0;
        if (eighthsLeft >= SIGN_EIGHTHS) {
            sign = rangeDecoder.decodeRawBits(1);
            eighthsLeft -= SIGN_EIGHTHS;
        }
        return 1.0f - 2.0f * sign;
    }

    // Time-frequency changes (RFC 6716 section 4.3.4.5): the folded band is recombined into fewer, longer blocks for
    // more frequency resolution, or split into more, shorter ones for more time resolution, as the band is.
    private void prepareTimeFrequency(final BandState state) {
        var change = timeFrequencyChanges[state.getBand()];
        if (change > 0) {
            state.setRecombine(change);
        }
        copyLowbandToScratch(state, change);
        recombineBlocks(state);
        change = divideBlocks(state, change);
        state.recordTimeFrequencySizes();
        deinterleaveLowband(state);
    }

    private void copyLowbandToScratch(final BandState state, final int change) {
        final var changed =
                state.getRecombine() != 0 || ((state.getBlockSize() & 1) == 0 && change < 0) || state.getBlocks() > 1;
        if (state.getLowband() != null && changed) {
            System.arraycopy(state.getLowband(), state.getLowbandOffset(), foldScratch, 0, state.getSize());
            state.useLowband(foldScratch);
        }
    }

    private void deinterleaveLowband(final BandState state) {
        if (state.getBlocksAfterTf() > 1 && state.getLowband() != null) {
            CeltVectorUtils.deinterleaveBlocks(
                    hadamardScratch,
                    state.getLowband(),
                    state.getLowbandOffset(),
                    state.getBlockSize() >> state.getRecombine(),
                    state.getBlocksAfterTf() << state.getRecombine(),
                    state.getBlockOrder());
        }
    }

    private void recombineBlocks(final BandState state) {
        for (var step = 0; step < state.getRecombine(); step++) {
            if (state.getLowband() != null) {
                CeltVectorUtils.transformHaar(
                        state.getLowband(), state.getLowbandOffset(), state.getSize() >> step, 1 << step);
            }
            state.interleaveFill();
        }
        state.finishRecombining();
    }

    private int divideBlocks(final BandState state, final int firstChange) {
        var change = firstChange;
        while ((state.getBlockSize() & 1) == 0 && change < 0) {
            if (state.getLowband() != null) {
                CeltVectorUtils.transformHaar(
                        state.getLowband(), state.getLowbandOffset(), state.getBlockSize(), state.getBlocks());
            }
            state.divideBlocksOnce();
            change++;
        }
        return change;
    }

    // Splits a mono band in two when its bits could code more pulses than a single vector may have.
    private void splitWideBand(final BandState state) {
        final var cacheStart =
                CeltCacheTables.INDEX[(state.getDuration() + 1) * CeltTables.BAND_COUNT + state.getBand()];
        state.setCacheStart(cacheStart);
        if (state.getCoding() == Coding.STEREO || state.getDuration() < 0 || state.getSize() <= 2) {
            return;
        }
        if (state.getBits() <= CeltCacheTables.BITS[cacheStart + CeltCacheTables.BITS[cacheStart]] + 12) {
            return;
        }
        state.halve();
    }

    private int decodeSplitBand(final int depth, final BandState state) {
        final var qalloc = decodeAngle(state);
        state.spendBits(qalloc);
        final var delta = prepareHalves(state);
        if (state.getSize() == 2 && state.getCoding() == Coding.STEREO) {
            return decodeTwoStereoCoefficients(depth, state, qalloc);
        }
        return decodeHalves(depth, state, qalloc, delta);
    }

    // Decodes the angle that splits the band's bits between its halves (RFC 6716 section 4.3.4.3), or the
    // inversion flag of a stereo band that has no angle. Returns the eighths of a bit it took.
    private int decodeAngle(final BandState state) {
        final var stereo = state.getCoding() == Coding.STEREO;
        final var pulseCap = CeltTables.LOG_BAND_WIDTH[state.getBand()] + state.getDuration() * 8;
        var quantizations = 1;
        if (!stereo || state.getBand() < intensityBand) {
            quantizations = quantizationCount(state, angleOffset(state, pulseCap), pulseCap);
        }
        final var tell = rangeDecoder.tellFraction();
        state.setTheta(0);
        state.setInversion(0);
        if (quantizations != 1) {
            state.setTheta(decodeTheta(state, quantizations) * FULL_ANGLE / quantizations);
        } else if (stereo) {
            state.setInversion(readInversion(state));
        }
        return rangeDecoder.tellFraction() - tell;
    }

    private int angleOffset(final BandState state, final int pulseCap) {
        if (state.getCoding() == Coding.STEREO && state.getSize() == 2) {
            return (pulseCap >> 1) - 16;
        }
        return (pulseCap >> 1) - 4;
    }

    private int readInversion(final BandState state) {
        if (state.getBits() > 2 << 3 && eighthsLeft > 2 << 3 && rangeDecoder.decodeBit(2)) {
            return 1;
        }
        return 0;
    }

    private int quantizationCount(final BandState state, final int offset, final int pulseCap) {
        var pairs = 2 * state.getSize() - 1;
        if (state.getCoding() == Coding.STEREO && state.getSize() == 2) {
            pairs--;
        }
        final var quantizationBits = Math.min(
                Math.min(state.getBits() - pulseCap - (4 << 3), (state.getBits() + pairs * offset) / pairs), 8 << 3);
        if (quantizationBits < 1 << 3 >> 1) {
            return 1;
        }
        final var scaled = CeltTables.QN_EXP2[quantizationBits & 7] >> (14 - (quantizationBits >> 3));
        return (scaled + 1) >> 1 << 1;
    }

    private int decodeTheta(final BandState state, final int quantizations) {
        if (state.getCoding() == Coding.STEREO && state.getSize() > 2) {
            return rangeDecoder.decodeStep(quantizations / 2);
        }
        if (state.getCoding() == Coding.STEREO || state.getBlocksAfterTf() > 1) {
            return rangeDecoder.decodeUniform(quantizations + 1);
        }
        return rangeDecoder.decodeTriangular(quantizations);
    }

    // Works out the gains of the two halves from the angle, and which blocks of the fill carry to each, and returns
    // the difference in bits that the halves are given.
    private int prepareHalves(final BandState state) {
        state.keepFillForHalves();
        final var theta = state.getTheta();
        if (theta == 0) {
            state.maskFill((1 << state.getBlocks()) - 1);
            state.setGains(32767, 0);
            return -FULL_ANGLE;
        }
        if (theta == FULL_ANGLE) {
            state.maskFill(((1 << state.getBlocks()) - 1) << state.getBlocks());
            state.setGains(0, 32767);
            return FULL_ANGLE;
        }
        final var mid = CeltPulseUtils.fixedPointCosine(theta);
        final var side = CeltPulseUtils.fixedPointCosine(FULL_ANGLE - theta);
        state.setGains(mid, side);
        return ((state.getSize() - 1) << 7) * CeltPulseUtils.logTangent(side, mid) + 16384 >> 15;
    }

    // A stereo band of two coefficients has one coded, and the other is that one turned a quarter turn.
    private int decodeTwoStereoCoefficients(final int depth, final BandState state, final int qalloc) {
        var sideBits = 0;
        if (state.getTheta() != 0 && state.getTheta() != FULL_ANGLE) {
            sideBits = 1 << 3;
        }
        eighthsLeft -= qalloc + sideBits;
        final var sign = 1 - 2 * readSignBit(sideBits);
        final var midBits = state.getBits() - sideBits;
        var mask = 0;
        if (state.getTheta() > HALF_ANGLE) {
            mask = decodeCarriedPair(depth, state, state.getY(), state.getYOffset(), midBits);
            turnPair(state.getY(), state.getYOffset(), state.getX(), state.getXOffset(), sign);
        } else {
            mask = decodeCarriedPair(depth, state, state.getX(), state.getXOffset(), midBits);
            turnPair(state.getX(), state.getXOffset(), state.getY(), state.getYOffset(), sign);
        }
        rotateStereoPair(state);
        return mask;
    }

    private int readSignBit(final int sideBits) {
        if (sideBits != 0) {
            return rangeDecoder.decodeRawBits(1);
        }
        return 0;
    }

    // Decodes the coded coefficients of the pair, which are in the array that the angle makes the carrier.
    private int decodeCarriedPair(
            final int depth, final BandState state, final float[] carrier, final int carrierOffset, final int midBits) {
        final var child = states[depth + 1];
        child.set(state.getBand(), carrier, carrierOffset, null, 0, state.getSize(), midBits, state.getBlocks());
        child.setLowband(
                state.getLowband(),
                state.getLowbandOffset(),
                state.getLowbandOut(),
                state.getLowbandOutOffset(),
                state.getDuration(),
                state.getLevel(),
                state.getGain(),
                state.getHalvesFill());
        return decodeBand(depth + 1);
    }

    private void turnPair(
            final float[] carrier,
            final int carrierOffset,
            final float[] other,
            final int otherOffset,
            final int sign) {
        other[otherOffset] = -sign * carrier[carrierOffset + 1];
        other[otherOffset + 1] = sign * carrier[carrierOffset];
    }

    private void rotateStereoPair(final BandState state) {
        final var x = state.getX();
        final var y = state.getY();
        final var xo = state.getXOffset();
        final var yo = state.getYOffset();
        x[xo] *= state.getMid();
        x[xo + 1] *= state.getMid();
        y[yo] *= state.getSide();
        y[yo + 1] *= state.getSide();
        var held = x[xo];
        x[xo] = held - y[yo];
        y[yo] = held + y[yo];
        held = x[xo + 1];
        x[xo + 1] = held - y[yo + 1];
        y[yo + 1] = held + y[yo + 1];
    }

    private int decodeHalves(final int depth, final BandState state, final int qalloc, final int theDelta) {
        final var delta = adjustedDelta(state, theDelta);
        final var midBits = CeltMathUtils.clip((state.getBits() - delta) / 2, 0, state.getBits());
        final var sideBits = state.getBits() - midBits;
        eighthsLeft -= qalloc;
        if (midBits >= sideBits) {
            return decodeMidFirst(depth, state, midBits, sideBits);
        }
        return decodeSideFirst(depth, state, midBits, sideBits);
    }

    // A band split in time gives its halves bits by how much of the angle each has.
    private int adjustedDelta(final BandState state, final int delta) {
        final var stereo = state.getCoding() == Coding.STEREO;
        if (state.getBlocksAfterTf() <= 1 || stereo || (state.getTheta() & 0x3fff) == 0) {
            return delta;
        }
        if (state.getTheta() > HALF_ANGLE) {
            return delta - (delta >> (4 - state.getDuration()));
        }
        return Math.min(0, delta + (state.getSize() << 3 >> (5 - state.getDuration())));
    }

    // The half that is decoded second is given the bits that the first left over, beyond a few.
    private int decodeMidFirst(final int depth, final BandState state, final int midBits, final int sideBits) {
        final var before = eighthsLeft;
        final var midMask = decodeHalf(depth, state, HalfKind.MID, midBits);
        final var rebalance = midBits - (before - eighthsLeft);
        var sideShare = sideBits;
        if (rebalance > 3 << 3 && state.getTheta() != 0) {
            sideShare += rebalance - (3 << 3);
        }
        final var sideMask = decodeHalf(depth, state, HalfKind.SIDE, sideShare);
        return midMask | sideMask << shiftOfSide(state);
    }

    private int decodeSideFirst(final int depth, final BandState state, final int midBits, final int sideBits) {
        final var before = eighthsLeft;
        final var sideMask = decodeHalf(depth, state, HalfKind.SIDE, sideBits);
        final var rebalance = sideBits - (before - eighthsLeft);
        var midShare = midBits;
        if (rebalance > 3 << 3 && state.getTheta() != FULL_ANGLE) {
            midShare += rebalance - (3 << 3);
        }
        final var midMask = decodeHalf(depth, state, HalfKind.MID, midShare);
        return midMask | sideMask << shiftOfSide(state);
    }

    // The collapse mask of the second half goes above the first's blocks, for a mono band split in time.
    private int shiftOfSide(final BandState state) {
        if (state.getCoding() == Coding.STEREO) {
            return 0;
        }
        return state.getBlocksAfterTf() >> 1;
    }

    private enum HalfKind {
        MID,
        SIDE
    }

    private int decodeHalf(final int depth, final BandState state, final HalfKind kind, final int bits) {
        final var child = states[depth + 1];
        switch (kind) {
            case MID -> setUpMidHalf(child, state, bits);
            case SIDE -> setUpSideHalf(child, state, bits);
        }
        return decodeBand(depth + 1);
    }

    private void setUpMidHalf(final BandState child, final BandState state, final int bits) {
        var gain = 1.0f;
        var level = 0;
        var lowbandOut = state.getLowbandOut();
        if (state.getCoding() != Coding.STEREO) {
            gain = state.getGain() * state.getMid();
            level = state.getLevel() + 1;
            lowbandOut = null;
        }
        child.set(state.getBand(), state.getX(), state.getXOffset(), null, 0, state.getSize(), bits, state.getBlocks());
        child.setLowband(
                state.getLowband(),
                state.getLowbandOffset(),
                lowbandOut,
                state.getLowbandOutOffset(),
                state.getDuration(),
                level,
                gain,
                state.getFill());
    }

    private void setUpSideHalf(final BandState child, final BandState state, final int bits) {
        var level = state.getLevel() + 1;
        var lowband = state.getLowband();
        if (state.getCoding() == Coding.STEREO) {
            level = 0;
            lowband = null;
        }
        child.set(state.getBand(), state.getY(), state.getYOffset(), null, 0, state.getSize(), bits, state.getBlocks());
        child.setLowband(
                lowband,
                state.getLowbandOffset() + state.getSize(),
                null,
                0,
                state.getDuration(),
                level,
                state.getGain() * state.getSide(),
                state.getFill() >> state.getBlocks());
    }

    // A band that is not split is a single pulse vector, or noise or a fold of a lower band when it has no pulses.
    private int decodeWholeBand(final BandState state) {
        var pulses = CeltPulseUtils.pulsesForBits(state.getCacheStart(), state.getBits());
        var spent = CeltPulseUtils.bitsForPulses(state.getCacheStart(), pulses);
        eighthsLeft -= spent;
        while (eighthsLeft < 0 && pulses > 0) {
            eighthsLeft += spent;
            pulses--;
            spent = CeltPulseUtils.bitsForPulses(state.getCacheStart(), pulses);
            eighthsLeft -= spent;
        }
        if (pulses != 0) {
            return decodePulses(state, CeltPulseUtils.pulsesOfIndex(pulses));
        }
        return fillWithoutPulses(state);
    }

    private int decodePulses(final BandState state, final int pulseCount) {
        final var total = CeltPulseUtils.vectorCount(state.getSize(), pulseCount);
        final var index = decodeUniformIndex(total);
        final var norm =
                (float) CeltPulseUtils.decodeVector(state.getSize(), pulseCount, index, pulseVector, pulseCursor);
        final var gain = state.getGain() / (float) Math.sqrt(norm);
        CeltVectorUtils.scalePulses(pulseVector, state.getX(), state.getXOffset(), state.getSize(), gain);
        CeltVectorUtils.spreadPulses(
                state.getX(), state.getXOffset(), state.getSize(), state.getBlocks(), pulseCount, spread);
        return CeltVectorUtils.collapseMask(pulseVector, state.getSize(), state.getBlocks());
    }

    // A uniform integer below a total that may need all 32 bits: its top bits are range coded, the rest raw.
    private long decodeUniformIndex(final long total) {
        final var bits = Long.SIZE - Long.numberOfLeadingZeros(total - 1);
        if (bits <= 8) {
            final var symbol = rangeDecoder.decode((int) total);
            rangeDecoder.update(symbol, symbol + 1, (int) total);
            return symbol;
        }
        final var rawBits = bits - 8;
        final var codedTotal = (int) (((total - 1) >> rawBits) + 1);
        final var symbol = rangeDecoder.decode(codedTotal);
        rangeDecoder.update(symbol, symbol + 1, codedTotal);
        final var index = ((long) symbol << rawBits) | (rangeDecoder.decodeRawBits(rawBits) & 0xFFFFFFFFL);
        return Math.min(index, total - 1);
    }

    private int fillWithoutPulses(final BandState state) {
        final var allBlocks = (1 << state.getBlocks()) - 1;
        state.maskFill(allBlocks);
        if (state.getFill() == 0) {
            Arrays.fill(state.getX(), state.getXOffset(), state.getXOffset() + state.getSize(), 0f);
            return 0;
        }
        var mask = allBlocks;
        if (state.getLowband() == null) {
            for (var index = 0; index < state.getSize(); index++) {
                state.getX()[state.getXOffset() + index] = noise.next() >> 20;
            }
        } else {
            foldLowband(state);
            mask = state.getFill();
        }
        CeltVectorUtils.renormalize(state.getX(), state.getXOffset(), state.getSize(), state.getGain());
        return mask;
    }

    private void foldLowband(final BandState state) {
        for (var index = 0; index < state.getSize(); index++) {
            var level = -FOLD_NOISE_LEVEL;
            if ((noise.next() & NOISE_BIT) != 0) {
                level = FOLD_NOISE_LEVEL;
            }
            state.getX()[state.getXOffset() + index] = state.getLowband()[state.getLowbandOffset() + index] + level;
        }
    }

    // What follows the decoding of the halves or the whole of a band: joins stereo halves, and takes a mono band back
    // from the block structure it was decoded in.
    private int finishBand(final BandState state, final int decodedMask) {
        if (state.getCoding() == Coding.STEREO) {
            finishStereoBand(state);
            return decodedMask;
        }
        if (state.getLevel() != 0) {
            return decodedMask;
        }
        return restoreBlocks(state, decodedMask);
    }

    private void finishStereoBand(final BandState state) {
        if (state.getSize() > 2) {
            CeltVectorUtils.mergeStereo(
                    state.getX(),
                    state.getXOffset(),
                    state.getY(),
                    state.getYOffset(),
                    state.getMid(),
                    state.getSize());
        }
        if (state.getInversion() != 0) {
            for (var index = 0; index < state.getSize(); index++) {
                state.getY()[state.getYOffset() + index] *= -1;
            }
        }
    }

    private int restoreBlocks(final BandState state, final int decodedMask) {
        interleaveBlocksAfterTimeFrequency(state);
        final var divided = undoTimeDivision(state, decodedMask);
        final var recombined = undoRecombination(state, divided);
        scaleForFolding(state);
        final var blocks = (state.getBlocksAfterTf() >> state.getTimeDivide()) << state.getRecombine();
        return recombined & ((1 << blocks) - 1);
    }

    private void interleaveBlocksAfterTimeFrequency(final BandState state) {
        if (state.getBlocksAfterTf() > 1) {
            CeltVectorUtils.interleaveBlocks(
                    hadamardScratch,
                    state.getX(),
                    state.getXOffset(),
                    state.getBlockSize() >> state.getRecombine(),
                    state.getBlocksAfterTf() << state.getRecombine(),
                    state.getBlockOrder());
        }
    }

    private int undoTimeDivision(final BandState state, final int decodedMask) {
        var mask = decodedMask;
        var blocks = state.getBlocksAfterTf();
        var blockSize = state.getBlockSizeAfterTf();
        for (var step = 0; step < state.getTimeDivide(); step++) {
            blocks >>= 1;
            blockSize <<= 1;
            mask |= mask >> blocks;
            CeltVectorUtils.transformHaar(state.getX(), state.getXOffset(), blockSize, blocks);
        }
        return mask;
    }

    private int undoRecombination(final BandState state, final int divideMask) {
        var mask = divideMask;
        for (var step = 0; step < state.getRecombine(); step++) {
            mask = CeltTables.BIT_DEINTERLEAVE[mask];
            CeltVectorUtils.transformHaar(state.getX(), state.getXOffset(), state.getFullSize() >> step, 1 << step);
        }
        return mask;
    }

    // Stores the band for later bands to fold from, scaled so that its energy follows its size.
    private void scaleForFolding(final BandState state) {
        if (state.getLowbandOut() == null) {
            return;
        }
        final var scale = (float) Math.sqrt(state.getFullSize());
        for (var index = 0; index < state.getFullSize(); index++) {
            state.getLowbandOut()[state.getLowbandOutOffset() + index] =
                    scale * state.getX()[state.getXOffset() + index];
        }
    }

    /**
     * Whether a band is decoded as one channel or as the two of a stereo pair.
     */
    private enum Coding {
        MONO,
        STEREO
    }

    /**
     * The arguments of one level of a band's decoding, and what that level works out along the way. It is rewritten
     * for each band.
     */
    @Getter
    private static final class BandState {
        private int band;
        private float[] x;
        private int xOffset;
        private float[] y;
        private int yOffset;
        private int size;
        private int bits;
        private int blocks;
        private float[] lowband;
        private int lowbandOffset;
        private float[] lowbandOut;
        private int lowbandOutOffset;
        private int duration;
        private int level;
        private float gain;
        private int fill;
        // Worked out while decoding.
        private Coding coding;
        private int fullSize;
        private int blocksAfterTf;
        private int blockSize;
        private int blockSizeAfterTf;

        @Setter
        private int recombine;

        private int timeDivide;
        private BlockOrder blockOrder;

        @Setter
        private int cacheStart;

        private int halvesFill;

        @Setter
        private int inversion;

        private float mid;
        private float side;

        @Setter
        private int theta;

        void set(
                final int newBand,
                final float[] newX,
                final int newXOffset,
                final float[] newY,
                final int newYOffset,
                final int newSize,
                final int newBits,
                final int newBlocks) {
            band = newBand;
            x = newX;
            xOffset = newXOffset;
            y = newY;
            yOffset = newYOffset;
            size = newSize;
            bits = newBits;
            blocks = newBlocks;
        }

        void setLowband(
                final float[] newLowband,
                final int newLowbandOffset,
                final float[] newOut,
                final int newOutOffset,
                final int newDuration,
                final int newLevel,
                final float newGain,
                final int newFill) {
            lowband = newLowband;
            lowbandOffset = newLowbandOffset;
            lowbandOut = newOut;
            lowbandOutOffset = newOutOffset;
            duration = newDuration;
            level = newLevel;
            gain = newGain;
            fill = newFill;
        }

        // Called once the arguments are set, before the band is decoded.
        void begin() {
            coding = Coding.MONO;
            if (y != null) {
                coding = Coding.STEREO;
            }
            fullSize = size;
            blocksAfterTf = blocks;
            blockSize = size / blocks;
            blockSizeAfterTf = blockSize;
            recombine = 0;
            timeDivide = 0;
            blockOrder = BlockOrder.SEQUENTIAL;
            if (blocks == 1) {
                blockOrder = BlockOrder.HADAMARD;
            }
        }

        // The lowband is replaced by a copy that the time-frequency changes may rewrite.
        void useLowband(final float[] copy) {
            lowband = copy;
            lowbandOffset = 0;
        }

        void recordTimeFrequencySizes() {
            blocksAfterTf = blocks;
            blockSizeAfterTf = blockSize;
        }

        // One step of recombining blocks: the fill of each pair of blocks is joined.
        void interleaveFill() {
            fill = CeltTables.BIT_INTERLEAVE[fill & 0xF] | CeltTables.BIT_INTERLEAVE[fill >> 4] << 2;
        }

        void finishRecombining() {
            blocks >>= recombine;
            blockSize <<= recombine;
        }

        // One step of dividing blocks: each block is split in two, with the fill of the first for both.
        void divideBlocksOnce() {
            fill |= fill << blocks;
            blocks <<= 1;
            blockSize >>= 1;
            timeDivide++;
        }

        // The band becomes its first half, with the second as the other channel's array at its offset.
        void halve() {
            size >>= 1;
            y = x;
            yOffset = xOffset + size;
            duration--;
            if (blocks == 1) {
                fill = (fill & 1) | (fill << 1);
            }
            blocks = (blocks + 1) >> 1;
        }

        void spendBits(final int spent) {
            bits -= spent;
        }

        void keepFillForHalves() {
            halvesFill = fill;
        }

        void maskFill(final int mask) {
            fill &= mask;
        }

        // The gains of the halves, as the fixed-point cosines of the angle give them.
        void setGains(final int fixedPointMid, final int fixedPointSide) {
            mid = fixedPointMid / 32768.0f;
            side = fixedPointSide / 32768.0f;
        }
    }
}
