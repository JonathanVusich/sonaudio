package dev.javax.sonaudio.format.mp3;

import dev.javax.sonaudio.exception.InvalidFormatException;

/**
 * The MPEG audio versions an MP3's frames may be in: MPEG-1 (ISO/IEC 11172-3), MPEG-2's lower sample rates (ISO/IEC
 * 13818-3), and the unofficial MPEG-2.5's lower ones still.
 */
public enum MpegVersion {
    MPEG_1(new int[] {44100, 48000, 32000}, Bitrates.MPEG_1),
    MPEG_2(new int[] {22050, 24000, 16000}, Bitrates.LSF),
    MPEG_2_5(new int[] {11025, 12000, 8000}, Bitrates.LSF);

    private static final int RESERVED_SAMPLE_RATE = 3;
    private static final int INVALID_BITRATE = 15;

    private final int[] sampleRates;
    // Each layer's bitrates in kbit/s, by the header's bitrate index. Index 0 is free format, and 15 is invalid.
    private final int[][] bitrates;

    MpegVersion(final int[] sampleRates, final int[][] bitrates) {
        this.sampleRates = sampleRates;
        this.bitrates = bitrates;
    }

    /**
     * @param versionBits a frame header's version field
     */
    public static MpegVersion fromBits(final int versionBits) throws InvalidFormatException {
        return switch (versionBits) {
            case 0b00 -> MPEG_2_5;
            case 0b10 -> MPEG_2;
            case 0b11 -> MPEG_1;
            default -> throw new InvalidFormatException("MP3 frame's MPEG version is reserved!");
        };
    }

    /**
     * @return how many long bands a mixed block has below its short bands: 36 lines, in either version
     */
    int mixedLongBands() {
        return switch (this) {
            case MPEG_1 -> 8;
            case MPEG_2, MPEG_2_5 -> 6;
        };
    }

    /**
     * @param index a frame header's sample rate index, of which 3 is reserved
     */
    public int sampleRate(final int index) throws InvalidFormatException {
        if (index == RESERVED_SAMPLE_RATE) {
            throw new InvalidFormatException("MP3 frame's sample rate is reserved!");
        }
        return sampleRates[index];
    }

    /**
     * @param index a frame header's bitrate index, of which 15 is invalid
     * @return the bitrate in bits per second, or {@link FrameHeader#FREE_FORMAT_BITRATE} for index 0, free format
     */
    public int bitrate(final Layer layer, final int index) throws InvalidFormatException {
        if (index == INVALID_BITRATE) {
            throw new InvalidFormatException("MP3 frame's bitrate index is invalid!");
        }
        return bitrates[layer.ordinal()][index] * 1000;
    }

    // ISO/IEC 11172-3 section 2.4.2.3, and ISO/IEC 13818-3 section 2.4.2.3, by layer.
    private static final class Bitrates {

        private static final int[][] MPEG_1 = {
            {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448},
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384},
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320}
        };
        // MPEG-2's Layers II and III share their bitrates, as does MPEG-2.5, which is MPEG-2 at half its sample rates.
        private static final int[] LSF_LAYERS_II_III = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};
        private static final int[][] LSF = {
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256}, LSF_LAYERS_II_III, LSF_LAYERS_II_III
        };
    }
}
