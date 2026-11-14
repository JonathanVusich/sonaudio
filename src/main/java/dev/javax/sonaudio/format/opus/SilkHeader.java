package dev.javax.sonaudio.format.opus;

/**
 * The flags at the head of an Opus frame's SILK layer: for each coded channel which of its SILK frames the voice
 * activity detector found active, and which of them are followed by a low bit rate redundancy copy of the previous
 * frame's.
 */
final class SilkHeader {

    private final int[] activity;
    private final int[] redundancy;

    /**
     * @param activity for each channel a bit mask with a bit for each SILK frame, set when it is active; this class
     *     keeps the array
     * @param redundancy for each channel a bit mask with a bit for each SILK frame, set when it has a redundancy copy;
     *     this class keeps the array
     */
    SilkHeader(final int[] activity, final int[] redundancy) {
        this.activity = activity;
        this.redundancy = redundancy;
    }

    SilkActivity activity(final int channel, final int frame) {
        if ((activity[channel] >> frame & 1) == 0) {
            return SilkActivity.INACTIVE;
        }
        return SilkActivity.ACTIVE;
    }

    /**
     * @return whether the frame of the channel has a redundancy copy after the header
     */
    boolean hasRedundancy(final int channel, final int frame) {
        return (redundancy[channel] >> frame & 1) != 0;
    }
}
