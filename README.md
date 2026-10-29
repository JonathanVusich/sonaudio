# sonaudio

Reads the tags, duration and decoded audio of any file FFmpeg can open, by calling FFmpeg's shared libraries from Java
with the foreign function API. It has no dependencies, and its API has no types of FFmpeg's: tags are strings, audio is
16-bit samples.

```java
final var ffmpeg = new FfmpegLibrary(); // once, shared
try (var channel = Files.newByteChannel(path);
        var file = new AudioFile(ffmpeg, channel)) {
    file.duration();                           // Optional<Duration>
    file.tags();                               // Map<String, List<String>>, under FFmpeg's names
    for (PcmChunk chunk : file.decodeMono(11025)) { // 16-bit mono at the rate asked for, as fpcalc converts it
        chunk.sample(0);
    }
}
```

The file is read through a `SeekableByteChannel`, and only as far as the chunks are iterated.

## Requirements

- Java 25, run with `--enable-native-access=ALL-UNNAMED`.
- **FFmpeg 7.1's shared libraries** (libavformat 61, libavcodec 61, libavutil 59, libswresample 5), as Debian 13 ships
  them. The library reads FFmpeg's structs at the offsets of that release, because FFmpeg 7.1 has no accessors for them,
  and it throws `IllegalStateException` for any other major version of libavformat rather than read the wrong bytes.
  Supporting another release means checking those offsets (`FfmpegLibrary`) against its headers with `offsetof`.

## Tests

The tests load FFmpeg into the JVM and use the `ffmpeg` program to make their audio, so they run in the image the
`Dockerfile` describes (Debian 13 with JDK 25), as CI does:

```bash
docker build -t sonaudio-test .
```

```bash
docker run --rm -v "$PWD:/workspace" sonaudio-test ./gradlew build javadoc
```

## What it doesn't do

- It converts audio only to mono 16-bit, which is what a fingerprint takes.
- It stops decoding at the first packet FFmpeg's decoder rejects, as `fpcalc` does.
- A bad file can't throw its way through a native frame, but a crash in FFmpeg itself would still end the JVM.
