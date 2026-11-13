package dev.javax.sonaudio.format.opus;

/**
 * What the bit allocation of a CELT frame decides for the frame as a whole, beside the bits of each band.
 *
 * @param spread the frame's spread decision
 * @param codedBands the band above the last that is coded: the bands from it to the frame's end are skipped
 * @param intensityBand the first band that is coded as intensity stereo, or 0 when none is
 * @param dualStereo how the bands below the intensity band are coded
 * @param anticollapseReserve the eighths of a bit kept for the anti-collapse bit: 8, or 0 when there is none
 * @param remainingBits the eighths of a bit that no band could take, which the first band with room gets
 */
record CeltAllocation(
        CeltSpread spread,
        int codedBands,
        int intensityBand,
        CeltStereoMode dualStereo,
        int anticollapseReserve,
        int remainingBits) {}
