package dev.javax.sonaudio.format.opus;

import dev.javax.sonaudio.format.opus.CeltVectorUtils.BlockOrder;
import java.util.Arrays;

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
    private static final double SQUARE_ROOT_OF_HALF = 0.70710678118654752440;

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
        rangeDecoder = decoder;
        frame = codedFrame;
        spread = allocation.spread();
        intensityBand = allocation.intensityBand();
        timeFrequencyChanges = changes;
        for (var channel = 0; channel < codedFrame.channels(); channel++) {
            Arrays.fill(coefficients[channel], 0, codedFrame.frameSamples(), 0f);
            Arrays.fill(collapseMasks[channel], 0);
        }
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
            if (dualStereo == CeltStereoMode.DUAL && band == intensityBand) {
                dualStereo = CeltStereoMode.JOINT;
                averageFoldedChannels(band);
            }
            findFoldSource(band, lowbandOffset);
            decodeChannels(band, bandBits, dualStereo);
            balance += bits.pulses(band) + consumed;
            foldUpdate = FoldUpdate.FROZEN;
            if (bandBits > frame.bandSize(band) << 3) {
                foldUpdate = FoldUpdate.ALLOWED;
            }
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

    private void averageFoldedChannels(final int band) {
        for (var index = frame.bandStart(frame.startBand()); index < frame.bandStart(band); index++) {
            folded[0][index] = (folded[0][index] + folded[1][index]) / 2;
        }
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
        var foldStart = lowbandOffset;
        do {
            foldStart--;
        } while (CeltTables.BAND_START[foldStart] > foldBin);
        var foldEnd = lowbandOffset - 1;
        do {
            foldEnd++;
        } while (foldEnd < band && CeltTables.BAND_START[foldEnd] < foldBin + width);
        foldFirstMask = 0;
        foldSecondMask = 0;
        for (var source = foldStart; source < foldEnd; source++) {
            foldFirstMask |= collapseMasks[0][source];
            foldSecondMask |= collapseMasks[frame.channels() - 1][source];
        }
    }

    private void decodeChannels(final int band, final int bandBits, final CeltStereoMode dualStereo) {
        final var allBlocks = (1 << frame.blocks()) - 1;
        var firstMask = allBlocks;
        var secondMask = allBlocks;
        if (fold == Fold.AVAILABLE) {
            firstMask = foldFirstMask;
            secondMask = foldSecondMask;
        }
        if (dualStereo == CeltStereoMode.DUAL) {
            firstMask = decodeTopBand(band, 0, null, bandBits >> 1, firstMask);
            secondMask = decodeTopBand(band, 1, null, bandBits >> 1, secondMask);
        } else {
            var second = coefficients[1];
            if (frame.channels() == 1) {
                second = null;
            }
            firstMask = decodeTopBand(band, 0, second, bandBits, firstMask | secondMask);
            secondMask = firstMask;
        }
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
        if (state.size == 1) {
            return decodeSingleCoefficient(state);
        }
        state.begin();
        if (state.coding == Coding.MONO && state.level == 0) {
            prepareTimeFrequency(state);
        }
        splitWideBand(state);
        var mask = 0;
        if (state.y != null) {
            mask = decodeSplitBand(depth, state);
        } else {
            mask = decodeWholeBand(state);
        }
        return finishBand(state, mask);
    }

    // A band of one coefficient has only a sign, per channel.
    private int decodeSingleCoefficient(final BandState state) {
        var vector = state.x;
        var offset = state.xOffset;
        var count = 1;
        if (state.y != null) {
            count = 2;
        }
        for (var channel = 0; channel < count; channel++) {
            var sign = 0;
            if (eighthsLeft >= SIGN_EIGHTHS) {
                sign = rangeDecoder.decodeRawBits(1);
                eighthsLeft -= SIGN_EIGHTHS;
            }
            vector[offset] = 1.0f - 2.0f * sign;
            vector = state.y;
            offset = state.yOffset;
        }
        if (state.lowbandOut != null) {
            state.lowbandOut[state.lowbandOutOffset] = state.x[state.xOffset];
        }
        return 1;
    }

    // Time-frequency changes (RFC 6716 section 4.3.4.5): the folded band is recombined into fewer, longer blocks for
    // more frequency resolution, or split into more, shorter ones for more time resolution, as the band is.
    private void prepareTimeFrequency(final BandState state) {
        var change = timeFrequencyChanges[state.band];
        if (change > 0) {
            state.recombine = change;
        }
        if (state.lowband != null
                && (state.recombine != 0 || ((state.blockSize & 1) == 0 && change < 0) || state.blocks > 1)) {
            System.arraycopy(state.lowband, state.lowbandOffset, foldScratch, 0, state.size);
            state.lowband = foldScratch;
            state.lowbandOffset = 0;
        }
        recombineBlocks(state);
        change = divideBlocks(state, change);
        state.blocksAfterTf = state.blocks;
        state.blockSizeAfterTf = state.blockSize;
        if (state.blocksAfterTf > 1 && state.lowband != null) {
            CeltVectorUtils.deinterleaveBlocks(
                    hadamardScratch,
                    state.lowband,
                    state.lowbandOffset,
                    state.blockSize >> state.recombine,
                    state.blocksAfterTf << state.recombine,
                    state.blockOrder);
        }
    }

    private void recombineBlocks(final BandState state) {
        for (var step = 0; step < state.recombine; step++) {
            if (state.lowband != null) {
                CeltVectorUtils.transformHaar(state.lowband, state.lowbandOffset, state.size >> step, 1 << step);
            }
            state.fill = CeltTables.BIT_INTERLEAVE[state.fill & 0xF] | CeltTables.BIT_INTERLEAVE[state.fill >> 4] << 2;
        }
        state.blocks >>= state.recombine;
        state.blockSize <<= state.recombine;
    }

    private int divideBlocks(final BandState state, final int firstChange) {
        var change = firstChange;
        while ((state.blockSize & 1) == 0 && change < 0) {
            if (state.lowband != null) {
                CeltVectorUtils.transformHaar(state.lowband, state.lowbandOffset, state.blockSize, state.blocks);
            }
            state.fill |= state.fill << state.blocks;
            state.blocks <<= 1;
            state.blockSize >>= 1;
            state.timeDivide++;
            change++;
        }
        return change;
    }

    // Splits a mono band in two when its bits could code more pulses than a single vector may have.
    private void splitWideBand(final BandState state) {
        final var cacheStart = CeltCacheTables.INDEX[(state.duration + 1) * CeltTables.BAND_COUNT + state.band];
        state.cacheStart = cacheStart;
        if (state.coding == Coding.STEREO || state.duration < 0 || state.size <= 2) {
            return;
        }
        if (state.bits <= CeltCacheTables.BITS[cacheStart + CeltCacheTables.BITS[cacheStart]] + 12) {
            return;
        }
        state.size >>= 1;
        state.y = state.x;
        state.yOffset = state.xOffset + state.size;
        state.duration--;
        if (state.blocks == 1) {
            state.fill = (state.fill & 1) | (state.fill << 1);
        }
        state.blocks = (state.blocks + 1) >> 1;
    }

    private int decodeSplitBand(final int depth, final BandState state) {
        final var qalloc = decodeAngle(state);
        state.bits -= qalloc;
        final var delta = prepareHalves(state);
        if (state.size == 2 && state.coding == Coding.STEREO) {
            return decodeTwoStereoCoefficients(depth, state, qalloc);
        }
        return decodeHalves(depth, state, qalloc, delta);
    }

    // Decodes the angle that splits the band's bits between its halves (RFC 6716 section 4.3.4.3), or the
    // inversion flag of a stereo band that has no angle. Returns the eighths of a bit it took.
    private int decodeAngle(final BandState state) {
        final var stereo = state.coding == Coding.STEREO;
        final var pulseCap = CeltTables.LOG_BAND_WIDTH[state.band] + state.duration * 8;
        var offset = (pulseCap >> 1) - 4;
        if (stereo && state.size == 2) {
            offset = (pulseCap >> 1) - 16;
        }
        var quantizations = 1;
        if (!stereo || state.band < intensityBand) {
            quantizations = quantizationCount(state, offset, pulseCap);
        }
        final var tell = rangeDecoder.tellFraction();
        state.theta = 0;
        state.inversion = 0;
        if (quantizations != 1) {
            state.theta = decodeTheta(state, quantizations) * FULL_ANGLE / quantizations;
        } else if (stereo) {
            if (state.bits > 2 << 3 && eighthsLeft > 2 << 3 && rangeDecoder.decodeBit(2)) {
                state.inversion = 1;
            }
        }
        return rangeDecoder.tellFraction() - tell;
    }

    private int quantizationCount(final BandState state, final int offset, final int pulseCap) {
        var pairs = 2 * state.size - 1;
        if (state.coding == Coding.STEREO && state.size == 2) {
            pairs--;
        }
        final var quantizationBits =
                Math.min(Math.min(state.bits - pulseCap - (4 << 3), (state.bits + pairs * offset) / pairs), 8 << 3);
        if (quantizationBits < 1 << 3 >> 1) {
            return 1;
        }
        final var scaled = CeltTables.QN_EXP2[quantizationBits & 7] >> (14 - (quantizationBits >> 3));
        return (scaled + 1) >> 1 << 1;
    }

    private int decodeTheta(final BandState state, final int quantizations) {
        if (state.coding == Coding.STEREO && state.size > 2) {
            return rangeDecoder.decodeStep(quantizations / 2);
        }
        if (state.coding == Coding.STEREO || state.blocksAfterTf > 1) {
            return rangeDecoder.decodeUniform(quantizations + 1);
        }
        return rangeDecoder.decodeTriangular(quantizations);
    }

    // Works out the gains of the two halves from the angle, and which blocks of the fill carry to each, and returns
    // the difference in bits that the halves are given.
    private int prepareHalves(final BandState state) {
        state.halvesFill = state.fill;
        var delta = 0;
        var mid = 32767;
        var side = 0;
        if (state.theta == 0) {
            state.fill &= (1 << state.blocks) - 1;
            delta = -FULL_ANGLE;
        } else if (state.theta == FULL_ANGLE) {
            mid = 0;
            side = 32767;
            state.fill &= ((1 << state.blocks) - 1) << state.blocks;
            delta = FULL_ANGLE;
        } else {
            mid = CeltPulseUtils.fixedPointCosine(state.theta);
            side = CeltPulseUtils.fixedPointCosine(FULL_ANGLE - state.theta);
            delta = ((state.size - 1) << 7) * CeltPulseUtils.logTangent(side, mid) + 16384 >> 15;
        }
        state.mid = mid / 32768.0f;
        state.side = side / 32768.0f;
        return delta;
    }

    private int decodeTwoStereoCoefficients(final int depth, final BandState state, final int qalloc) {
        var sideBits = 0;
        if (state.theta != 0 && state.theta != FULL_ANGLE) {
            sideBits = 1 << 3;
        }
        final var midBits = state.bits - sideBits;
        eighthsLeft -= qalloc + sideBits;
        var sign = 0;
        if (sideBits != 0) {
            sign = rangeDecoder.decodeRawBits(1);
        }
        sign = 1 - 2 * sign;
        final var swapped = state.theta > HALF_ANGLE;
        final var child = states[depth + 1];
        final var firstArray = state.x;
        final var secondArray = state.y;
        var carrier = firstArray;
        var carrierOffset = state.xOffset;
        var other = secondArray;
        var otherOffset = state.yOffset;
        if (swapped) {
            carrier = secondArray;
            carrierOffset = state.yOffset;
            other = firstArray;
            otherOffset = state.xOffset;
        }
        child.set(state.band, carrier, carrierOffset, null, 0, state.size, midBits, state.blocks);
        child.setLowband(
                state.lowband,
                state.lowbandOffset,
                state.lowbandOut,
                state.lowbandOutOffset,
                state.duration,
                state.level,
                state.gain,
                state.halvesFill);
        final var mask = decodeBand(depth + 1);
        other[otherOffset] = -sign * carrier[carrierOffset + 1];
        other[otherOffset + 1] = sign * carrier[carrierOffset];
        rotateStereoPair(state);
        return mask;
    }

    private void rotateStereoPair(final BandState state) {
        final var x = state.x;
        final var y = state.y;
        final var xo = state.xOffset;
        final var yo = state.yOffset;
        x[xo] *= state.mid;
        x[xo + 1] *= state.mid;
        y[yo] *= state.side;
        y[yo + 1] *= state.side;
        var held = x[xo];
        x[xo] = held - y[yo];
        y[yo] = held + y[yo];
        held = x[xo + 1];
        x[xo + 1] = held - y[yo + 1];
        y[yo + 1] = held + y[yo + 1];
    }

    private int decodeHalves(final int depth, final BandState state, final int qalloc, final int theDelta) {
        var delta = theDelta;
        final var stereo = state.coding == Coding.STEREO;
        if (state.blocksAfterTf > 1 && !stereo && (state.theta & 0x3fff) != 0) {
            if (state.theta > HALF_ANGLE) {
                delta -= delta >> (4 - state.duration);
            } else {
                delta = Math.min(0, delta + (state.size << 3 >> (5 - state.duration)));
            }
        }
        var midBits = CeltMathUtils.clip((state.bits - delta) / 2, 0, state.bits);
        var sideBits = state.bits - midBits;
        eighthsLeft -= qalloc;
        final var before = eighthsLeft;
        if (midBits >= sideBits) {
            final var midMask = decodeHalf(depth, state, HalfKind.MID, midBits);
            var rebalance = midBits - (before - eighthsLeft);
            if (rebalance > 3 << 3 && state.theta != 0) {
                sideBits += rebalance - (3 << 3);
            }
            final var sideMask = decodeHalf(depth, state, HalfKind.SIDE, sideBits);
            return midMask | sideMask << shiftOfSide(state);
        }
        final var sideMask = decodeHalf(depth, state, HalfKind.SIDE, sideBits);
        final var rebalance = sideBits - (before - eighthsLeft);
        if (rebalance > 3 << 3 && state.theta != FULL_ANGLE) {
            midBits += rebalance - (3 << 3);
        }
        final var midMask = decodeHalf(depth, state, HalfKind.MID, midBits);
        return midMask | sideMask << shiftOfSide(state);
    }

    // The collapse mask of the second half goes above the first's blocks, for a mono band split in time.
    private int shiftOfSide(final BandState state) {
        if (state.coding == Coding.STEREO) {
            return 0;
        }
        return state.blocksAfterTf >> 1;
    }

    private enum HalfKind {
        MID,
        SIDE
    }

    private int decodeHalf(final int depth, final BandState state, final HalfKind kind, final int bits) {
        final var stereo = state.coding == Coding.STEREO;
        final var child = states[depth + 1];
        final var nextLevel = state.level + 1;
        switch (kind) {
            case MID -> {
                var gain = 1.0f;
                var level = 0;
                if (!stereo) {
                    gain = state.gain * state.mid;
                    level = nextLevel;
                }
                child.set(state.band, state.x, state.xOffset, null, 0, state.size, bits, state.blocks);
                child.setLowband(
                        state.lowband,
                        state.lowbandOffset,
                        midOut(state),
                        midOutOffset(state),
                        state.duration,
                        level,
                        gain,
                        state.fill);
            }
            case SIDE -> {
                var level = 0;
                if (!stereo) {
                    level = nextLevel;
                }
                var sideLowband = state.lowband;
                if (stereo) {
                    sideLowband = null;
                }
                child.set(state.band, state.y, state.yOffset, null, 0, state.size, bits, state.blocks);
                child.setLowband(
                        sideLowband,
                        state.lowbandOffset + state.size,
                        null,
                        0,
                        state.duration,
                        level,
                        state.gain * state.side,
                        state.fill >> state.blocks);
            }
        }
        return decodeBand(depth + 1);
    }

    private float[] midOut(final BandState state) {
        if (state.coding == Coding.STEREO) {
            return state.lowbandOut;
        }
        return null;
    }

    private int midOutOffset(final BandState state) {
        return state.lowbandOutOffset;
    }

    // A band that is not split is a single pulse vector, or noise or a fold of a lower band when it has no pulses.
    private int decodeWholeBand(final BandState state) {
        var pulses = CeltPulseUtils.pulsesForBits(state.cacheStart, state.bits);
        var spent = CeltPulseUtils.bitsForPulses(state.cacheStart, pulses);
        eighthsLeft -= spent;
        while (eighthsLeft < 0 && pulses > 0) {
            eighthsLeft += spent;
            pulses--;
            spent = CeltPulseUtils.bitsForPulses(state.cacheStart, pulses);
            eighthsLeft -= spent;
        }
        if (pulses != 0) {
            return decodePulses(state, CeltPulseUtils.pulsesOfIndex(pulses));
        }
        return fillWithoutPulses(state);
    }

    private int decodePulses(final BandState state, final int pulseCount) {
        final var total = CeltPulseUtils.vectorCount(state.size, pulseCount);
        final var index = decodeUniformIndex(total);
        final var norm = (float) CeltPulseUtils.decodeVector(state.size, pulseCount, index, pulseVector, pulseCursor);
        final var gain = state.gain / (float) Math.sqrt(norm);
        CeltVectorUtils.scalePulses(pulseVector, state.x, state.xOffset, state.size, gain);
        CeltVectorUtils.spreadPulses(state.x, state.xOffset, state.size, state.blocks, pulseCount, spread);
        return CeltVectorUtils.collapseMask(pulseVector, state.size, state.blocks);
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
        final var allBlocks = (1 << state.blocks) - 1;
        state.fill &= allBlocks;
        if (state.fill == 0) {
            Arrays.fill(state.x, state.xOffset, state.xOffset + state.size, 0f);
            return 0;
        }
        var mask = allBlocks;
        if (state.lowband == null) {
            for (var index = 0; index < state.size; index++) {
                state.x[state.xOffset + index] = noise.next() >> 20;
            }
        } else {
            foldLowband(state);
            mask = state.fill;
        }
        CeltVectorUtils.renormalize(state.x, state.xOffset, state.size, state.gain);
        return mask;
    }

    private void foldLowband(final BandState state) {
        for (var index = 0; index < state.size; index++) {
            var level = -FOLD_NOISE_LEVEL;
            if ((noise.next() & NOISE_BIT) != 0) {
                level = FOLD_NOISE_LEVEL;
            }
            state.x[state.xOffset + index] = state.lowband[state.lowbandOffset + index] + level;
        }
    }

    // What follows the decoding of the halves or the whole of a band: joins stereo halves, and takes a mono band back
    // from the block structure it was decoded in.
    private int finishBand(final BandState state, final int decodedMask) {
        var mask = decodedMask;
        if (state.coding == Coding.STEREO) {
            if (state.size > 2) {
                CeltVectorUtils.mergeStereo(state.x, state.xOffset, state.y, state.yOffset, state.mid, state.size);
            }
            if (state.inversion != 0) {
                for (var index = 0; index < state.size; index++) {
                    state.y[state.yOffset + index] *= -1;
                }
            }
            return mask;
        }
        if (state.level != 0) {
            return mask;
        }
        mask = restoreBlocks(state, mask);
        return mask;
    }

    private int restoreBlocks(final BandState state, final int decodedMask) {
        var mask = decodedMask;
        if (state.blocksAfterTf > 1) {
            CeltVectorUtils.interleaveBlocks(
                    hadamardScratch,
                    state.x,
                    state.xOffset,
                    state.blockSize >> state.recombine,
                    state.blocksAfterTf << state.recombine,
                    state.blockOrder);
        }
        var blocks = state.blocksAfterTf;
        var blockSize = state.blockSizeAfterTf;
        for (var step = 0; step < state.timeDivide; step++) {
            blocks >>= 1;
            blockSize <<= 1;
            mask |= mask >> blocks;
            CeltVectorUtils.transformHaar(state.x, state.xOffset, blockSize, blocks);
        }
        for (var step = 0; step < state.recombine; step++) {
            mask = CeltTables.BIT_DEINTERLEAVE[mask];
            CeltVectorUtils.transformHaar(state.x, state.xOffset, state.fullSize >> step, 1 << step);
        }
        blocks <<= state.recombine;
        scaleForFolding(state);
        return mask & ((1 << blocks) - 1);
    }

    // Stores the band for later bands to fold from, scaled so that its energy follows its size.
    private void scaleForFolding(final BandState state) {
        if (state.lowbandOut == null) {
            return;
        }
        final var scale = (float) Math.sqrt(state.fullSize);
        for (var index = 0; index < state.fullSize; index++) {
            state.lowbandOut[state.lowbandOutOffset + index] = scale * state.x[state.xOffset + index];
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
    private static final class BandState {
        int band;
        float[] x;
        int xOffset;
        float[] y;
        int yOffset;
        int size;
        int bits;
        int blocks;
        float[] lowband;
        int lowbandOffset;
        float[] lowbandOut;
        int lowbandOutOffset;
        int duration;
        int level;
        float gain;
        int fill;
        // Worked out while decoding.
        Coding coding;
        int fullSize;
        int blocksAfterTf;
        int blockSize;
        int blockSizeAfterTf;
        int recombine;
        int timeDivide;
        BlockOrder blockOrder;
        int cacheStart;
        int halvesFill;
        int inversion;
        float mid;
        float side;
        int theta;

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
    }
}
