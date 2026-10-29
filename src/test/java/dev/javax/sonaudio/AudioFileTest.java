package dev.javax.sonaudio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.assertj.core.data.Percentage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AudioFileTest {

    private static final int RATE = 11025;
    private static final int TONE_SECONDS = 3;
    private static final int TONE_FREQUENCY = 440;
    // The tone's peak is half of full scale, and FFmpeg upmixes it to stereo and mixes it back to mono at 3 dB less.
    private static final double TONE_RMS = 0.5 / Math.sqrt(2) / Math.sqrt(2) * Short.MAX_VALUE;

    private static FfmpegLibrary ffmpeg;

    @TempDir
    Path directory;

    // The sample format is fixed for the lossless encodings, as FFmpeg would pick a wider one for some of them, and Opus
    // only encodes at 48 kHz and a few lower rates.
    enum Encoding {
        FLAC(44100, "flac", "-c:a", "flac", "-sample_fmt", "s16"),
        MP3(44100, "mp3", "-c:a", "libmp3lame", "-id3v2_version", "3"),
        AAC(44100, "m4a", "-c:a", "aac"),
        VORBIS(44100, "ogg", "-c:a", "libvorbis"),
        OPUS(48000, "opus", "-c:a", "libopus"),
        WAV(44100, "wav", "-c:a", "pcm_s16le"),
        ALAC(44100, "m4a", "-c:a", "alac", "-sample_fmt", "s16p"),
        WAVPACK(44100, "wv", "-c:a", "wavpack", "-sample_fmt", "s16p");

        private final int sampleRate;
        private final String extension;
        private final String[] arguments;

        Encoding(final int sampleRate, final String extension, final String... arguments) {
            this.sampleRate = sampleRate;
            this.extension = extension;
            this.arguments = arguments;
        }

        int sampleRate() {
            return sampleRate;
        }

        String extension() {
            return extension;
        }

        String[] arguments() {
            return arguments.clone();
        }
    }

    enum LosslessEncoding {
        FLAC(Encoding.FLAC),
        WAV(Encoding.WAV),
        ALAC(Encoding.ALAC),
        WAVPACK(Encoding.WAVPACK);

        private final Encoding encoding;

        LosslessEncoding(final Encoding encoding) {
            this.encoding = encoding;
        }

        Encoding encoding() {
            return encoding;
        }
    }

    @BeforeAll
    static void loadFfmpeg() {
        ffmpeg = new FfmpegLibrary();
    }

    @ParameterizedTest
    @EnumSource(Encoding.class)
    void readsTheTagsOfEveryFormat(final Encoding encoding) throws Exception {
        final var file = tone(encoding, 2);

        try (final var channel = Files.newByteChannel(file);
                final var audio = new AudioFile(ffmpeg, channel)) {
            assertThat(audio.tags().get("title")).containsExactly("Song");
            assertThat(audio.tags().get("artist")).containsExactly("Artist");
            assertThat(audio.tags().get("album")).containsExactly("Album");
        }
    }

    @ParameterizedTest
    @EnumSource(Encoding.class)
    void readsTheDurationOfEveryFormat(final Encoding encoding) throws Exception {
        final var file = tone(encoding, TONE_SECONDS);

        try (final var channel = Files.newByteChannel(file);
                final var audio = new AudioFile(ffmpeg, channel)) {
            assertThat(audio.duration().orElseThrow())
                    .isCloseTo(Duration.ofSeconds(TONE_SECONDS), Duration.ofMillis(100));
        }
    }

    @ParameterizedTest
    @EnumSource(Encoding.class)
    void decodesEveryFormatToTheToneAtTheRequestedRate(final Encoding encoding) throws Exception {
        final var file = tone(encoding, TONE_SECONDS);

        final var samples = decode(file, RATE);

        assertThat(samples.length).isCloseTo(TONE_SECONDS * RATE, Percentage.withPercentage(5));
        assertThat(rootMeanSquare(samples)).isCloseTo(TONE_RMS,Percentage.withPercentage(10));
        assertThat(zeroCrossings(samples)).isCloseTo(2 * TONE_FREQUENCY * TONE_SECONDS, Percentage.withPercentage(5));
    }

    @ParameterizedTest
    @EnumSource(LosslessEncoding.class)
    void losslessFormatsDecodeToTheSameSamples(final LosslessEncoding lossless) throws Exception {
        final var reference = decode(monoTone(Encoding.WAV), RATE);

        final var samples = decode(monoTone(lossless.encoding()), RATE);

        assertThat(samples).isEqualTo(reference);
    }

    @Test
    void readsTheFileOnlyAsFarAsTheChunksAreIterated() throws Exception {
        final var file = directory.resolve("noise.flac");
        FfmpegProgram.encodeNoise(file, 30);

        try (final var channel = new CountingChannel(Files.newByteChannel(file));
                final var audio = new AudioFile(ffmpeg, channel)) {
            final var chunks = audio.decodeMono(RATE);
            assertThat(chunks.iterator().next().sampleCount()).isPositive();

            assertThat(channel.bytesRead()).isLessThan(Files.size(file) / 4);
        }
    }

    @Test
    void refusesToDecodeTheAudioTwice() throws Exception {
        final var file = tone(Encoding.FLAC, 1);

        try (final var channel = Files.newByteChannel(file);
                final var audio = new AudioFile(ffmpeg, channel)) {
            audio.decodeMono(RATE);

            assertThatThrownBy(() -> audio.decodeMono(RATE)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void refusesBytesThatAreNotAudio() throws Exception {
        final var file = directory.resolve("garbage.m4a");
        Files.write(file, new byte[5000]);

        try (final var channel = Files.newByteChannel(file)) {
            assertThatThrownBy(() -> new AudioFile(ffmpeg, channel)).isInstanceOf(UnreadableAudioException.class);
        }
    }

    @Test
    void refusesAnEmptyFile() throws Exception {
        final var file = Files.createFile(directory.resolve("empty.flac"));

        try (final var channel = Files.newByteChannel(file)) {
            assertThatThrownBy(() -> new AudioFile(ffmpeg, channel)).isInstanceOf(UnreadableAudioException.class);
        }
    }

    @Test
    void decodesTheStartOfAFileCutShort() throws Exception {
        final var whole = tone(Encoding.MP3, 10);
        final var cut = directory.resolve("cut.mp3");
        Files.write(cut, Arrays.copyOf(Files.readAllBytes(whole), 50_000));

        final var samples = decode(cut, RATE);

        assertThat(samples.length).isPositive();
    }

    private Path tone(final Encoding encoding, final int seconds) throws IOException, InterruptedException {
        final var file = directory.resolve("tone-%s.%s".formatted(encoding, encoding.extension()));
        FfmpegProgram.encodeTone(file, seconds, 2, encoding.sampleRate(), encoding.arguments());
        return file;
    }

    // A tone at the rate it's decoded to, so a lossless format has nothing to resample.
    private Path monoTone(final Encoding encoding) throws IOException, InterruptedException {
        final var file = directory.resolve("mono-%s.%s".formatted(encoding, encoding.extension()));
        FfmpegProgram.encodeTone(file, 2, 1, RATE, encoding.arguments());
        return file;
    }

    private static short[] decode(final Path file, final int sampleRate) throws Exception {
        try (final var channel = Files.newByteChannel(file);
                final var audio = new AudioFile(ffmpeg, channel)) {
            final var chunks = new ArrayList<PcmChunk>();
            for (final var chunk : audio.decodeMono(sampleRate)) {
                chunks.add(chunk);
            }
            return flatten(chunks);
        }
    }

    private static short[] flatten(final List<PcmChunk> chunks) {
        final var total = chunks.stream().mapToInt(PcmChunk::sampleCount).sum();
        final var samples = new short[total];
        var offset = 0;
        for (final var chunk : chunks) {
            for (var i = 0; i < chunk.sampleCount(); i++) {
                samples[offset + i] = chunk.sample(i);
            }
            offset += chunk.sampleCount();
        }
        return samples;
    }

    private static double rootMeanSquare(final short[] samples) {
        var sum = 0.0;
        for (final var sample : samples) {
            sum += (double) sample * sample;
        }
        return Math.sqrt(sum / samples.length);
    }

    private static int zeroCrossings(final short[] samples) {
        var crossings = 0;
        for (var i = 1; i < samples.length; i++) {
            if ((samples[i - 1] < 0) != (samples[i] < 0)) {
                crossings++;
            }
        }
        return crossings;
    }
}
