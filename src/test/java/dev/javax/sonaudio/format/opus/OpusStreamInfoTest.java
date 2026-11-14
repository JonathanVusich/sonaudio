package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.javax.sonaudio.format.opus.ChannelSource.Coded;
import dev.javax.sonaudio.format.opus.ChannelSource.Copied;
import dev.javax.sonaudio.format.opus.ChannelSource.Silent;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OpusStreamInfoTest {

    @Test
    void mapsFiveDotOneFromVorbisOrderToFfmpegOrder() {
        // The channel order of FFmpeg's 5.1 (FL FR FC LFE BL BR) from Vorbis's (FL FC FR BL BR LFE) and the mapping
        // table of a libopus 5.1 file, whose first two streams are coupled.
        final var info = info(ChannelMappingFamily.VORBIS_ORDER, 6, 4, 2, 0, 4, 1, 2, 3, 5);

        assertThat(info.channelSources())
                .containsExactly(
                        new Coded(0, 0),
                        new Coded(0, 1),
                        new Coded(2, 0),
                        new Coded(3, 0),
                        new Coded(1, 0),
                        new Coded(1, 1));
    }

    @Test
    void mapsAMonoAndAStereoFileToTheirOneStream() {
        assertThat(info(ChannelMappingFamily.SINGLE_STREAM, 1, 1, 0, 0, 1).channelSources())
                .containsExactly(new Coded(0, 0));
        assertThat(info(ChannelMappingFamily.SINGLE_STREAM, 2, 1, 1, 0, 1).channelSources())
                .containsExactly(new Coded(0, 0), new Coded(0, 1));
    }

    @Test
    void mapsUnmappedChannelsToSilenceAndRepeatedOnesToCopies() {
        final var info = info(ChannelMappingFamily.UNDEFINED, 4, 2, 0, 1, 255, 1, 0);

        assertThat(info.channelSources())
                .containsExactly(new Coded(1, 0), new Silent(), new Copied(0), new Coded(0, 0));
    }

    @Test
    void hasTheLengthAsADurationOfSamplesAt48Kilohertz() {
        final var info = info(ChannelMappingFamily.SINGLE_STREAM, 1, 1, 0, 0, 1);

        assertThat(info.withSampleCount(11520).duration()).isEqualTo(Duration.ofMillis(240));
        assertThat(info.withSampleCount(48000 * 3600L + 1).duration()).isEqualTo(Duration.ofSeconds(3600, 20_833));
        assertThat(info.duration()).isEqualTo(Duration.ZERO);
    }

    @Test
    void keepsItsChannelMappingToItself() {
        final var info = info(ChannelMappingFamily.SINGLE_STREAM, 2, 1, 1, 0, 1);

        info.channelMapping()[0] = 9;

        assertThat(info.channelMapping()).containsExactly(0, 1);
    }

    private OpusStreamInfo info(
            final ChannelMappingFamily family,
            final int channelCount,
            final int streams,
            final int coupled,
            final int... mapping) {
        return new OpusStreamInfo(1, channelCount, 312, 48000, 0, family, streams, coupled, mapping, 0);
    }
}
