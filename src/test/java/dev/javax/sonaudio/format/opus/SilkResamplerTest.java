package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Checks the resampler against FFmpeg 9.0.2's {@code aresample} (libswresample with {@code filter_size=16}, as the Opus
 * decoder configures it): the same signal, preceded by the silence the decoder feeds first, resampled to 48 kHz by
 * {@code ffmpeg -f f32le -ar <rate> -i in.f32 -af aresample=out_sample_rate=48000:filter_size=16 -f f32le out.f32}.
 */
class SilkResamplerTest {

    private static final int FRAME_MILLIS = 20;
    private static final int OUTPUT_FRAME = 960;
    private static final float TOLERANCE = 2e-6f;

    // The output samples of the reference at some indices.
    private record Reference(
            OpusBandwidth bandwidth, int firstFrameSamples, int[] indices, float[] values, int flushedSamples) {}

    private static final Reference NARROWBAND = new Reference(
            OpusBandwidth.NARROWBAND,
            936,
            new int[] {0, 5, 100, 400, 935, 936, 937, 959, 960, 961, 1500, 1895, 1896, 1897, 1900, 1919},
            new float[] {
                0.0f,
                0.011209906078875065f,
                -0.12214340269565582f,
                -0.2783946096897125f,
                -0.2629939913749695f,
                0.0f,
                0.26299402117729187f,
                -0.46826648712158203f,
                -0.640625f,
                -0.7421771883964539f,
                0.6484375f,
                -0.3276419937610626f,
                -0.5f,
                -0.6015884280204773f,
                -0.3673279583454132f,
                0.39531880617141724f
            },
            24);

    private static final Reference MEDIUMBAND = new Reference(
            OpusBandwidth.MEDIUMBAND,
            960,
            new int[] {0, 5, 100, 400, 935, 936, 937, 959, 960, 961, 1500, 1895, 1896, 1897, 1900, 1919},
            new float[] {
                0.0f,
                -0.0005467927549034357f,
                0.421875f,
                0.1875f,
                0.8304546475410461f,
                0.7109375f,
                0.43483853340148926f,
                -0.1616644263267517f,
                -0.25f,
                -0.1537255346775055f,
                -0.671875f,
                -0.42283105850219727f,
                -0.0390625f,
                0.3447059392929077f,
                0.5859375f,
                0.6898294687271118f
            },
            0);

    private static final Reference WIDEBAND = new Reference(
            OpusBandwidth.WIDEBAND,
            960,
            new int[] {0, 5, 100, 400, 935, 936, 937, 959, 960, 961, 1500, 1895, 1896, 1897, 1900, 1919},
            new float[] {
                0.0f,
                0.0f,
                0.248337060213089f,
                0.5858194828033447f,
                0.5303512215614319f,
                0.4765625f,
                0.19072873890399933f,
                0.8795925378799438f,
                0.765625f,
                0.3799578845500946f,
                0.203125f,
                -0.13776850700378418f,
                -0.5234375f,
                -0.6374032497406006f,
                0.5936319828033447f,
                -0.07646070420742035f
            },
            0);

    static Stream<Reference> references() {
        return Stream.of(NARROWBAND, MEDIUMBAND, WIDEBAND);
    }

    @ParameterizedTest
    @MethodSource("references")
    void resamplesTwoFramesAsLibswresampleDoes(final Reference reference) {
        final var resampler = new SilkResampler(1);
        final var inputFrame = reference.bandwidth().silkSampleRate() * FRAME_MILLIS / 1000;
        final var output = new float[1][2 * OUTPUT_FRAME + OUTPUT_FRAME];
        resampler.open(reference.bandwidth());

        final var first = resampler.convert(channels(signal(0, inputFrame)), inputFrame, output, 0, OUTPUT_FRAME);
        final var second =
                resampler.convert(channels(signal(inputFrame, inputFrame)), inputFrame, output, first, OUTPUT_FRAME);
        resampler.flush(output, first + second, reference.flushedSamples());

        assertThat(first).isEqualTo(reference.firstFrameSamples());
        assertThat(second).isEqualTo(OUTPUT_FRAME);
        for (var index = 0; index < reference.indices().length; index++) {
            assertThat(output[0][reference.indices()[index]])
                    .as("output sample %d", reference.indices()[index])
                    .isCloseTo(reference.values()[index], within(TOLERANCE));
        }
    }

    @Test
    void passesAConstantSignalThroughOnceTheSilenceHasPassed() {
        final var resampler = new SilkResampler(1);
        final var input = new float[1][320];
        Arrays.fill(input[0], 0.5f);
        final var output = new float[1][OUTPUT_FRAME];
        resampler.open(OpusBandwidth.WIDEBAND);

        resampler.convert(input, 320, output, 0, OUTPUT_FRAME);

        for (var index = 100; index < 800; index++) {
            assertThat(output[0][index]).as("sample %d", index).isCloseTo(0.5f, within(0.01f));
        }
    }

    @Test
    void resamplesEachChannelTheSameWay() {
        final var resampler = new SilkResampler(2);
        final var input = new float[][] {signal(0, 160), signal(0, 160)};
        final var output = new float[2][OUTPUT_FRAME];
        resampler.open(OpusBandwidth.NARROWBAND);

        final var written = resampler.convert(input, 160, output, 0, OUTPUT_FRAME);

        assertThat(written).isEqualTo(936);
        assertThat(output[1]).isEqualTo(output[0]);
    }

    @Test
    void startsAgainFromSilenceWhenOpenedAfterBeingClosed() {
        final var input = channels(signal(0, 240));
        final var used = new SilkResampler(1);
        used.open(OpusBandwidth.MEDIUMBAND);
        used.convert(input, 240, new float[1][OUTPUT_FRAME], 0, OUTPUT_FRAME);
        used.convert(input, 240, new float[1][OUTPUT_FRAME], 0, OUTPUT_FRAME);
        used.close();
        used.open(OpusBandwidth.MEDIUMBAND);
        final var again = new float[1][OUTPUT_FRAME];
        final var fresh = new SilkResampler(1);
        fresh.open(OpusBandwidth.MEDIUMBAND);
        final var expected = new float[1][OUTPUT_FRAME];

        used.convert(input, 240, again, 0, OUTPUT_FRAME);
        fresh.convert(input, 240, expected, 0, OUTPUT_FRAME);

        assertThat(again[0]).isEqualTo(expected[0]);
    }

    @Test
    void reportsItselfClosedUntilOpened() {
        final var resampler = new SilkResampler(1);

        assertThat(resampler.state()).isEqualTo(ResamplerState.CLOSED);

        resampler.open(OpusBandwidth.WIDEBAND);
        assertThat(resampler.state()).isEqualTo(ResamplerState.OPEN);
        assertThat(resampler.inputSampleRate()).isEqualTo(16000);

        resampler.close();
        assertThat(resampler.state()).isEqualTo(ResamplerState.CLOSED);
    }

    private float[][] channels(final float[] samples) {
        return new float[][] {samples};
    }

    // A signal of integers only, which has the same floats in FFmpeg's reference: a different one each sample.
    private float[] signal(final int start, final int count) {
        final var samples = new float[count];
        for (var index = 0; index < count; index++) {
            samples[index] = ((((start + index) * 7919) % 201) - 100) / 128.0f;
        }
        return samples;
    }
}
