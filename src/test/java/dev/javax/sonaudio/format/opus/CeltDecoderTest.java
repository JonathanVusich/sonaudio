package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.javax.sonaudio.exception.InvalidFormatException;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CeltDecoderTest {

    private static RangeDecoder decoderOf(final byte[] frame) {
        final var decoder = new RangeDecoder();
        decoder.initialise(frame, 0, frame.length);
        return decoder;
    }

    private static byte[] randomFrame(final long seed, final int bytes) {
        final var frame = new byte[bytes];
        new Random(seed).nextBytes(frame);
        return frame;
    }

    @Test
    void rejectsAFrameSizeThatIsNotAMultipleOfTheShortBlock() {
        final var decoder = new CeltDecoder(1);
        final var output = new float[1][CeltDecoder.MAX_FRAME_SAMPLES];

        assertThatThrownBy(() ->
                        decoder.decodeFrame(decoderOf(randomFrame(1, 40)), ChannelCoding.MONO, 360, 0, 21, output))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void rejectsBandsOutsideTheFrame() {
        final var decoder = new CeltDecoder(1);
        final var output = new float[1][CeltDecoder.MAX_FRAME_SAMPLES];

        assertThatThrownBy(
                        () -> decoder.decodeFrame(decoderOf(randomFrame(1, 40)), ChannelCoding.MONO, 480, 5, 4, output))
                .isInstanceOf(InvalidFormatException.class);
        assertThatThrownBy(() ->
                        decoder.decodeFrame(decoderOf(randomFrame(1, 40)), ChannelCoding.MONO, 480, 0, 22, output))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void anEmptyFrameDecodesToSilence() throws InvalidFormatException {
        final var decoder = new CeltDecoder(2);
        final var output = new float[2][CeltDecoder.MAX_FRAME_SAMPLES];
        output[0][5] = 1f;

        decoder.decodeFrame(decoderOf(new byte[0]), ChannelCoding.STEREO, 960, 0, 21, output);

        for (var channel = 0; channel < 2; channel++) {
            for (var index = 0; index < 960; index++) {
                assertThat(output[channel][index]).isZero();
            }
        }
    }

    // Whatever the bits, a frame decodes to finite samples in about the range of a signal.
    @ParameterizedTest
    @ValueSource(ints = {120, 240, 480, 960})
    void decodesArbitraryBitsToFiniteSamples(final int frameSamples) throws InvalidFormatException {
        for (final var coding : ChannelCoding.values()) {
            final var decoder = new CeltDecoder(2);
            final var output = new float[2][CeltDecoder.MAX_FRAME_SAMPLES];
            for (var seed = 0; seed < 20; seed++) {
                decoder.decodeFrame(decoderOf(randomFrame(seed, 20 + 10 * seed)), coding, frameSamples, 0, 21, output);
                assertSamplesFinite(output, frameSamples);
            }
        }
    }

    private void assertSamplesFinite(final float[][] output, final int frameSamples) {
        for (final var channel : output) {
            for (var index = 0; index < frameSamples; index++) {
                assertThat(Float.isFinite(channel[index])).isTrue();
            }
        }
    }

    @Test
    void aMonoFrameOutputAsStereoGivesBothChannelsTheSameSamples() throws InvalidFormatException {
        final var decoder = new CeltDecoder(2);
        final var output = new float[2][CeltDecoder.MAX_FRAME_SAMPLES];

        decoder.decodeFrame(decoderOf(randomFrame(3, 120)), ChannelCoding.MONO, 480, 0, 21, output);

        assertThat(output[1]).isEqualTo(output[0]);
    }

    @Test
    void aFrameDecodesTheSameAfterAResetAsInAFreshDecoder() throws InvalidFormatException {
        final var used = new CeltDecoder(1);
        final var fresh = new CeltDecoder(1);
        final var usedOutput = new float[1][CeltDecoder.MAX_FRAME_SAMPLES];
        final var freshOutput = new float[1][CeltDecoder.MAX_FRAME_SAMPLES];
        used.decodeFrame(decoderOf(randomFrame(1, 150)), ChannelCoding.MONO, 960, 0, 21, usedOutput);
        used.decodeFrame(decoderOf(randomFrame(2, 150)), ChannelCoding.MONO, 960, 0, 21, usedOutput);

        used.reset();
        used.decodeFrame(decoderOf(randomFrame(4, 150)), ChannelCoding.MONO, 960, 0, 21, usedOutput);
        fresh.decodeFrame(decoderOf(randomFrame(4, 150)), ChannelCoding.MONO, 960, 0, 21, freshOutput);

        assertThat(usedOutput[0]).isEqualTo(freshOutput[0]);
    }

    @Test
    void aHybridFrameCodesOnlyTheBandsFromTheHybridStart() throws InvalidFormatException {
        final var decoder = new CeltDecoder(1);
        final var output = new float[1][CeltDecoder.MAX_FRAME_SAMPLES];

        decoder.decodeFrame(
                decoderOf(randomFrame(5, 60)), ChannelCoding.MONO, 480, CeltDecoder.HYBRID_START_BAND, 21, output);

        assertSamplesFinite(output, 480);
    }

    @Test
    void aStereoFrameOutputAsMonoIsTheAverageOfItsChannels() throws InvalidFormatException {
        final var stereo = new CeltDecoder(2);
        final var mono = new CeltDecoder(1);
        final var stereoOutput = new float[2][CeltDecoder.MAX_FRAME_SAMPLES];
        final var monoOutput = new float[1][CeltDecoder.MAX_FRAME_SAMPLES];
        final var frame = randomFrame(6, 200);

        stereo.decodeFrame(decoderOf(frame), ChannelCoding.STEREO, 480, 0, 21, stereoOutput);
        mono.decodeFrame(decoderOf(frame), ChannelCoding.STEREO, 480, 0, 21, monoOutput);

        for (var index = 0; index < 480; index++) {
            final var average = (stereoOutput[0][index] + stereoOutput[1][index]) / 2;
            assertThat(monoOutput[0][index]).isCloseTo(average, within(1e-5f));
        }
    }
}
