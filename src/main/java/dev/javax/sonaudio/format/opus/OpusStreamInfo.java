package dev.javax.sonaudio.format.opus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * What an Ogg Opus file's {@code OpusHead} packet says of its stream (RFC 7845 section 5.1), with the length its last
 * page's granule position gives.
 *
 * @param version the header's version, whose major part (the high four bits) is 0
 * @param channelCount how many channels the file decodes to, from 1
 * @param preSkip how many 48 kHz samples the decoder drops from the start
 * @param inputSampleRate the rate the audio was recorded at, which is only information: decoding is always at 48 kHz
 * @param outputGain the gain to apply on decoding, in Q7.8 dB (the header's signed 16-bit integer)
 * @param mappingFamily how the channels map to coded streams
 * @param streamCount how many coded streams the file has
 * @param coupledStreamCount how many of the streams, the first ones, are stereo
 * @param channelMapping for each output channel, which of the streams' channels it takes: a coupled stream's two
 *     channels, then the others, with 255 for silence. Family 0 has none in its header, so it holds the mapping it
 *     implies: 0, then 1 for stereo.
 * @param sampleCount how many samples per channel the audio holds once the pre-skip is dropped, as the granule
 *     position of the file's last page gives it: 0 when no page has one
 */
public record OpusStreamInfo(
        int version,
        int channelCount,
        int preSkip,
        int inputSampleRate,
        int outputGain,
        ChannelMappingFamily mappingFamily,
        int streamCount,
        int coupledStreamCount,
        int[] channelMapping,
        long sampleCount) {

    private static final int OPUS_SAMPLE_RATE = 48000;
    private static final int UNMAPPED_CHANNEL = 255;
    // For each channel count from 1 to 8, which of the header's table FFmpeg's output channel at each place reads.
    private static final int[][] VORBIS_TO_FFMPEG_ORDER = {
        {0},
        {0, 1},
        {0, 2, 1},
        {0, 1, 2, 3},
        {0, 2, 1, 3, 4},
        {0, 2, 1, 5, 3, 4},
        {0, 2, 1, 6, 5, 3, 4},
        {0, 2, 1, 7, 5, 6, 3, 4}
    };
    private static final double Q8_DIVISOR = 256.0;

    @Override
    public int[] channelMapping() {
        return channelMapping.clone();
    }

    /**
     * @return how long the audio plays once the pre-skip is dropped. FFmpeg's container duration is the granule position
     *     itself, so 312 samples (6.5 ms) longer for the usual pre-skip.
     */
    public Duration duration() {
        final var seconds = sampleCount / OPUS_SAMPLE_RATE;
        final var nanos = sampleCount % OPUS_SAMPLE_RATE * 1_000_000_000L / OPUS_SAMPLE_RATE;
        return Duration.ofSeconds(seconds, nanos);
    }

    /**
     * @return {@link #outputGain} in decibels
     */
    public double outputGainDecibels() {
        return outputGain / Q8_DIVISOR;
    }

    /**
     * Works out where each output channel gets its samples from, as FFmpeg's decoder maps them: for the family of
     * Vorbis's channel order, an output channel takes the channel of the header's table that FFmpeg's layout puts at its
     * place, and a channel that names a coded channel an earlier one named is a copy of it.
     *
     * @return the source of each output channel, in order
     */
    List<ChannelSource> channelSources() {
        final var sources = new ArrayList<ChannelSource>();
        for (var channel = 0; channel < channelCount; channel++) {
            sources.add(channelSource(channel));
        }
        return List.copyOf(sources);
    }

    private ChannelSource channelSource(final int channel) {
        final var coded = channelMapping[tableIndex(channel)];
        if (coded == UNMAPPED_CHANNEL) {
            return new ChannelSource.Silent();
        }
        for (var earlier = 0; earlier < channel; earlier++) {
            if (channelMapping[tableIndex(earlier)] == coded) {
                return new ChannelSource.Copied(earlier);
            }
        }
        if (coded < 2 * coupledStreamCount) {
            return new ChannelSource.Coded(coded / 2, coded & 1);
        }
        return new ChannelSource.Coded(coded - coupledStreamCount, 0);
    }

    // Vorbis's order puts the center before the right, and the surround and rear channels at other places than
    // FFmpeg's.
    private int tableIndex(final int channel) {
        if (mappingFamily == ChannelMappingFamily.VORBIS_ORDER) {
            return VORBIS_TO_FFMPEG_ORDER[channelCount - 1][channel];
        }
        return channel;
    }

    /**
     * @param sampleCount the length to give the info, once the file's last page is known
     * @return this info with another length
     */
    OpusStreamInfo withSampleCount(final long sampleCount) {
        return new OpusStreamInfo(
                version,
                channelCount,
                preSkip,
                inputSampleRate,
                outputGain,
                mappingFamily,
                streamCount,
                coupledStreamCount,
                channelMapping,
                sampleCount);
    }
}
