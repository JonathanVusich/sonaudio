package dev.javax.sonaudio;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code ffmpeg} program, which the tests use to make their audio files: the container has it beside the
 * libraries the code under test loads.
 */
final class FfmpegProgram {

    private FfmpegProgram() {}

    /**
     * Encodes a generated sine tone to the file, tagged as a music player would tag it.
     */
    static void encodeTone(
            final Path file, final int seconds, final int channels, final int sampleRate, final String... encoding)
            throws IOException, InterruptedException {
        final var tone = "sine=frequency=440:duration=%d:sample_rate=44100,volume=4".formatted(seconds);
        final var command = new ArrayList<>(List.of("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", tone));
        command.addAll(List.of("-ac", Integer.toString(channels), "-ar", Integer.toString(sampleRate)));
        command.addAll(List.of("-metadata", "title=Song", "-metadata", "artist=Artist"));
        command.addAll(List.of("-metadata", "album=Album", "-metadata", "album_artist=Album Artist"));
        command.addAll(List.of(encoding));
        command.add(file.toString());
        run(command);
    }

    /**
     * Encodes pink noise, which doesn't compress, so the file is long enough that reading all of it shows.
     */
    static void encodeNoise(final Path file, final int seconds) throws IOException, InterruptedException {
        final var noise = "anoisesrc=color=pink:duration=%d:sample_rate=44100".formatted(seconds);
        run(List.of("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", noise, "-ac", "2", file.toString()));
    }

    private static void run(final List<String> command) throws IOException, InterruptedException {
        final var process = new ProcessBuilder(command).inheritIO().start();
        final var exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("%s exited with %d".formatted(command, exitCode));
        }
    }
}
