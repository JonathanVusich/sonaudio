package dev.javax.sonaudio.samples;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.time.Duration;
import lombok.experimental.UtilityClass;
import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.flac.FlacParser;
import dev.javax.sonaudio.format.flac.FlacWriter;
import dev.javax.sonaudio.metadata.RawMetadata;

/**
 * Tiny synthetic FLAC files for tests, written by {@link FlacWriter}.
 */
@UtilityClass
public final class FlacFiles {

    // How long the silence is that every file gets as its audio.
    private static final Duration AUDIO_DURATION = Duration.ofSeconds(10);

    /**
     * @param metadata ten seconds long, as the audio is
     * @return the metadata written as FLAC, followed by the audio frames of {@link FlacAudioSample#SILENCE_10_SECONDS},
     * which the {@link FlacWriter}'s stream info matches, so the file decodes and can be fingerprinted
     */
    public static byte[] flacFile(final RawMetadata metadata) {
        if (!metadata.duration().equals(AUDIO_DURATION)) {
            throw new IllegalArgumentException("Test FLAC files are %s long".formatted(AUDIO_DURATION));
        }
        final var outputStream = new ByteArrayOutputStream();
        try {
            new FlacWriter().writeMetadata(metadata, outputStream);
            outputStream.write(silentFrames());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return outputStream.toByteArray();
    }

    private static byte[] silentFrames() throws IOException {
        try (final var channel = FlacAudioSample.SILENCE_10_SECONDS.channel()) {
            // Parsing leaves the channel at the first audio frame.
            new FlacParser().parse(channel);
            return Channels.newInputStream(channel).readAllBytes();
        } catch (InvalidFormatException e) {
            throw new IllegalStateException("The silence sample isn't valid FLAC", e);
        }
    }
}
