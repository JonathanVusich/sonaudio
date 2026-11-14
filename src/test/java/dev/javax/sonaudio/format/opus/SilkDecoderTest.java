package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.javax.sonaudio.exception.InvalidFormatException;
import org.junit.jupiter.api.Test;

/**
 * Decodes a frame that the test encodes with the RFC's range encoder: a 10 ms narrowband mono frame that the voice
 * activity detector found inactive, with no pulses. The decoder is checked against FFmpeg's on real files by
 * {@link OpusDecoderParityTest}.
 */
class SilkDecoderTest {

    private static final int TABLE_BITS = 8;
    private static final int FRAME_SAMPLES = 80;
    private static final int STAGE_ONE_INDEX = 5;
    private static final int MIDDLE_SYMBOL = 4;
    private static final int BLOCKS = 5;

    @Test
    void rejectsBandwidthsAboveWideband() {
        final var decoder = new SilkDecoder(1);

        assertThatThrownBy(() -> decoder.decodeFrame(
                        rangeDecoder(), OpusBandwidth.SUPERWIDEBAND, ChannelCoding.MONO, 20, new float[1][960]))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void rejectsFramesLongerThanSixtyMilliseconds() {
        final var decoder = new SilkDecoder(1);

        assertThatThrownBy(() -> decoder.decodeFrame(
                        rangeDecoder(), OpusBandwidth.WIDEBAND, ChannelCoding.MONO, 80, new float[1][960]))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void decodesAFrameToAsManySamplesAsItIsLong() throws InvalidFormatException {
        final var decoder = new SilkDecoder(1);
        final var output = new float[1][SilkDecoder.MAX_FRAME_SAMPLES];

        final var samples =
                decoder.decodeFrame(inactiveMonoFrame(), OpusBandwidth.NARROWBAND, ChannelCoding.MONO, 10, output);

        assertThat(samples).isEqualTo(FRAME_SAMPLES);
        assertThat(output[0]).as("samples after the frame are left alone").endsWith(0f);
        assertThat(output[0][FRAME_SAMPLES - 1]).isNotZero();
    }

    @Test
    void givesBothChannelsTheSamplesOfAMonoFrameWhenTheStreamIsStereo() throws InvalidFormatException {
        final var decoder = new SilkDecoder(2);
        final var output = new float[2][SilkDecoder.MAX_FRAME_SAMPLES];

        decoder.decodeFrame(inactiveMonoFrame(), OpusBandwidth.NARROWBAND, ChannelCoding.MONO, 10, output);

        assertThat(output[1]).isEqualTo(output[0]);
        assertThat(output[0][FRAME_SAMPLES - 1]).isNotZero();
    }

    @Test
    void delaysItsOutputByTwoSamples() throws InvalidFormatException {
        final var decoder = new SilkDecoder(1);
        final var output = new float[1][SilkDecoder.MAX_FRAME_SAMPLES];

        decoder.decodeFrame(inactiveMonoFrame(), OpusBandwidth.NARROWBAND, ChannelCoding.MONO, 10, output);

        // The frame's first two samples are those before the frame, which are silence.
        assertThat(output[0][0]).isZero();
        assertThat(output[0][1]).isZero();
        assertThat(output[0][2]).isNotZero();
    }

    @Test
    void forgetsTheLastFrameWhenReset() throws InvalidFormatException {
        final var decoder = new SilkDecoder(1);
        final var first = new float[1][SilkDecoder.MAX_FRAME_SAMPLES];
        final var again = new float[1][SilkDecoder.MAX_FRAME_SAMPLES];
        decoder.decodeFrame(inactiveMonoFrame(), OpusBandwidth.NARROWBAND, ChannelCoding.MONO, 10, first);

        decoder.reset();
        decoder.decodeFrame(inactiveMonoFrame(), OpusBandwidth.NARROWBAND, ChannelCoding.MONO, 10, again);

        assertThat(again[0]).isEqualTo(first[0]);
    }

    @Test
    void remembersTheLastFrameWhenNotReset() throws InvalidFormatException {
        final var decoder = new SilkDecoder(1);
        final var first = new float[1][SilkDecoder.MAX_FRAME_SAMPLES];
        final var second = new float[1][SilkDecoder.MAX_FRAME_SAMPLES];
        decoder.decodeFrame(inactiveMonoFrame(), OpusBandwidth.NARROWBAND, ChannelCoding.MONO, 10, first);

        decoder.decodeFrame(inactiveMonoFrame(), OpusBandwidth.NARROWBAND, ChannelCoding.MONO, 10, second);

        assertThat(second[0]).isNotEqualTo(first[0]);
    }

    private RangeDecoder rangeDecoder() {
        final var bytes = new byte[64];
        final var rangeDecoder = new RangeDecoder();
        rangeDecoder.initialise(bytes, 0, bytes.length);
        return rangeDecoder;
    }

    // The header bits (inactive, without redundancy), the frame type, two gains, the filter and the excitation.
    private RangeDecoder inactiveMonoFrame() {
        final var encoder = new RangeEncoder(256);
        encoder.encodeBit(0, 1);
        encoder.encodeBit(0, 1);
        encoder.encodeIcdf(0, SilkModels.FRAME_TYPE_INACTIVE, TABLE_BITS);
        encoder.encodeIcdf(3, SilkModels.GAIN_HIGHBITS[0], TABLE_BITS);
        encoder.encodeIcdf(2, SilkModels.GAIN_LOWBITS, TABLE_BITS);
        encoder.encodeIcdf(MIDDLE_SYMBOL, SilkModels.GAIN_DELTA, TABLE_BITS);
        encoder.encodeIcdf(STAGE_ONE_INDEX, SilkModels.LSF_S1[0][0], TABLE_BITS);
        for (var index = 0; index < 10; index++) {
            final var model = SilkModels.LSF_S2[SilkTables.LSF_S2_SEL_NBMB[STAGE_ONE_INDEX][index]];
            encoder.encodeIcdf(MIDDLE_SYMBOL, model, TABLE_BITS);
        }
        encoder.encodeIcdf(0, SilkModels.LCG_SEED, TABLE_BITS);
        encoder.encodeIcdf(0, SilkModels.EXC_RATE[0], TABLE_BITS);
        for (var block = 0; block < BLOCKS; block++) {
            encoder.encodeIcdf(0, SilkModels.PULSE_COUNT[0], TABLE_BITS);
        }
        final var bytes = encoder.finish();
        final var rangeDecoder = new RangeDecoder();
        rangeDecoder.initialise(bytes, 0, bytes.length);
        return rangeDecoder;
    }
}
