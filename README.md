# sonaudio

Hand-written readers and decoders for FLAC, MP3, ALAC and Opus files, the tag model they fill in, and the fingerprints
AcoustID identifies recordings by. It's pure Java: no native code, and no audio libraries behind it. It aims to match the output of FFmpeg and
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
final var parser = new FlacParser(); // or an Mp3Parser, AlacParser or OpusParser

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
  ones, as MP3 decodes to. `PcmAudio` is sealed, so a `switch` over a parser's audio needs a case for each kind and
  no default. `AudioUtils.measureDuration` measures how long audio plays.
- `dev.javax.sonaudio.format.flac`: FLAC (RFC 9639): its metadata blocks, a decoder that checks each frame's CRCs, and a
  writer of metadata.
- `dev.javax.sonaudio.format.mp3`: MPEG audio of Layers I, II and III. ID3v2.2 to 2.4 and ID3v1 tags, Xing, Info and
  VBRI headers with LAME's encoder delay and padding, and a decoder whose samples are FFmpeg's.
- `dev.javax.sonaudio.format.alac`: ALAC (Apple Lossless) in MPEG-4 files. The boxes of the movie, its tags and gapless
  information (the edit list, `mdhd` and iTunes' `iTunSMPB`), and a decoder of 16 and 24-bit audio whose samples are
  FFmpeg's bit for bit, on every fixture and on FFmpeg's own test files. 20 and 32-bit files decode as the format says,
  but nothing here can write one to check them against.
- `dev.javax.sonaudio.format.opus`: Opus in Ogg files (RFC 6716, RFC 7845). The headers, Vorbis comments and the length
  from the last granule position, and a decoder of SILK, CELT and hybrid frames, mode switches and multistream files.
  It follows FFmpeg's native decoder, not libopus: its samples are within 1 of FFmpeg's at 16 bits, and as many, on every
  fixture and every FATE vector. They aren't bit for bit, because FFmpeg's own output changes by 1 here and there between
  its C and SIMD code, and this is the C arithmetic.
- `dev.javax.sonaudio.metadata`: the format-agnostic tag model: `RawMetadata`, `Tag`, `TagValue`.
- `dev.javax.sonaudio.fingerprint`: Chromaprint. Its fingerprints match `fpcalc`'s: identical for 552 of 555 real
  MP3 tracks tried, and 1 bit off for the other three, which AcoustID matches to the same recordings, and identical for
  every ALAC and Opus file checked, 6-channel Opus included.
- `dev.javax.sonaudio.exception`: `InvalidFormatException`.

The module exports only these packages, and their classes that aren't part of the API are package-private.

## Building

`./gradlew build javadoc` builds everything, checks its formatting (Spotless, with palantir-java-format) and runs the
tests, which need nothing but Java 25. `./gradlew spotlessApply` formats the sources.

The comparisons against real files and `fpcalc`'s output (`Mp3CorpusComparisonTest`, `Mp3FingerprintComparisonTest`)
read their inputs from environment variables, and are skipped when those aren't set. They're not in git: they stay
local. The FATE tests (see References) skip the same way.

## References

Every reference the tests compare against (FFmpeg's decoded samples, `fpcalc`'s fingerprints) comes from one Docker
image, built from source at pinned versions: FFmpeg 9.0.2 with its native ALAC and Opus decoders (and libopus 1.6.1 to
encode with), and Chromaprint 1.6.2's `fpcalc` built on that FFmpeg. `reference/Dockerfile` has the versions and
checksums, and `reference/README.md` the rest. The ALAC and Opus fixtures under `src/test/resources` come from it:
`reference/make-fixtures.sh` makes them again, byte for byte, and each constant of `AlacSample` and `OpusSample` says
how its file was made and what's in it.

FFmpeg's own test cases (its FATE suite) are a bigger check, and they run locally only. `reference/prepare-fate.sh`
builds the image if it's missing, downloads the ~75 MB of FATE samples it needs and makes their references, all into
the git-ignored `.fate-samples/` (or into the directory you give it); a second run does nothing that's already done.
The tests read that directory, or the one `FATE_SAMPLES` names, and are skipped when there isn't one. So FATE is
verified locally only: CI runs the committed fixtures and their references, which are the same kinds of check on
smaller files, and skips FATE. On the FATE vectors the Opus decoder meets FATE's own criterion (the standard deviation
from RFC 8251's decode is within 3 of each vector's target) and is within 1 of FFmpeg's samples; the ALAC decoder equals
FFmpeg's.

## License

[MIT](LICENSE).
