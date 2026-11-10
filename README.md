# sonaudio

Hand-written readers for audio files, the tag model they fill in, and the fingerprints AcoustID identifies recordings
by. It's pure Java: no native code, and no audio libraries behind it. It aims to match the output of FFmpeg and
`fpcalc` (the same decoded samples, the same fingerprints), but it isn't a clone of either: where the code says it does
something "as FFmpeg does", it's reproducing that result, not FFmpeg's code, and it doesn't call either of them.

It started as the audio layer of [Sona](https://github.com/JonathanVusich/sona), a self-hosted music server.

## Installing

sonaudio needs Java 25. It's on Maven Central as `dev.javax:sonaudio`:

```kotlin
dependencies {
    implementation("dev.javax:sonaudio:0.1.0-RC")
}
```

```xml
<dependency>
    <groupId>dev.javax</groupId>
    <artifactId>sonaudio</artifactId>
    <version>0.1.0-RC</version>
</dependency>
```

It's a module, `dev.javax.sonaudio`, which needs only [bitstream](https://github.com/JonathanVusich/bitstream)
(`dev.javax:bitstream`). On the module path, `requires dev.javax.sonaudio;`. On the class path it works as any other
jar.

## Using it

A parser reads a file's tags through a `SeekableByteChannel`, and hands out a decoder that reads the audio from the
same channel a frame at a time, so nothing is decoded unless it's asked for. Each frame can be read only once, so
measuring the duration, which reads all of the audio, and fingerprinting, which reads its first two minutes, each
parse the file.

```java
final var parser = new FlacParser(); // or new Mp3Parser()

try (final var channel = Files.newByteChannel(path)) {
    final var parsed = parser.parse(channel);
    final var title = parsed.metadata().findValues(Tag.TRACK_TITLE).stream()
            .findFirst()
            .flatMap(TagValue::asString);
    final var fingerprint = new Fingerprint(parsed.audio());
    final var acoustId = fingerprint.compressed(); // as fpcalc prints it
}

try (final var channel = Files.newByteChannel(path)) {
    // Decodes every frame, rather than trusting the duration in the file's header.
    final var duration = AudioUtils.measureDuration(parser.parse(channel).audio());
}
```

Parsers and decoders throw `InvalidFormatException` for a file that's malformed, rather than skip or guess. The
`Parser` interface and `Format` cover every format, for code that picks a parser by a file's extension.

## Packages

- `dev.javax.sonaudio.format`: what every format shares: `Parser`, `Writer`, `Format`, and the decoded audio, of one
  kind each: `IntPcmAudio` gives integer frames, as a lossless format decodes to, and `FloatPcmAudio` floating-point
  ones, as MP3 decodes to. `AudioUtils.measureDuration` measures how long audio plays.
- `dev.javax.sonaudio.format.flac`: FLAC (RFC 9639): its metadata blocks, a decoder that checks each frame's CRCs, and a
  writer of metadata.
- `dev.javax.sonaudio.format.mp3`: MPEG audio of Layers I, II and III. ID3v2.2 to 2.4 and ID3v1 tags, Xing, Info and
  VBRI headers with LAME's encoder delay and padding, and a decoder whose samples are FFmpeg's.
- `dev.javax.sonaudio.metadata`: the format-agnostic tag model: `RawMetadata`, `Tag`, `TagValue`.
- `dev.javax.sonaudio.fingerprint`: Chromaprint. Its fingerprints match `fpcalc`'s: identical for 552 of 555 real
  tracks tried, and 1 bit off for the other three, which AcoustID matches to the same recordings.
- `dev.javax.sonaudio.exception`: `InvalidFormatException`.

The module exports only these packages, and their classes that aren't part of the API are package-private.

## Building

`./gradlew build javadoc` builds everything, checks its formatting (Spotless, with palantir-java-format) and runs the
tests, which need nothing but Java 25. `./gradlew spotlessApply` formats the sources.

The comparisons against real files and `fpcalc`'s output (`Mp3CorpusComparisonTest`, `Mp3FingerprintComparisonTest`)
read their inputs from environment variables, and are skipped when those aren't set. They're not in git: they stay
local.

## License

[MIT](LICENSE).
