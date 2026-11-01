# sonaudio

Sona's audio layer as a library: hand-written readers for audio files, the tag model they fill in, and the
fingerprints AcoustID identifies recordings by.

- `format`: FLAC and MP3 parsers and decoders, a FLAC writer, and the PCM types decoders hand out (`IntPcmAudio`,
  `FloatPcmAudio`). A parser reads a file's tags and duration through a `SeekableByteChannel`, and its decoder reads the
  audio from the same channel a frame at a time, so nothing is decoded unless it's iterated.
- `metadata`: the format-agnostic tag model (`RawMetadata`, `Tag`, `TagValue`).
- `fingerprint`: Chromaprint, bit for bit with `fpcalc`. `Fingerprint.compressed()` is the base64 form AcoustID takes.

Parsers throw `InvalidFormatException` for a file that's malformed, rather than skip or guess.

## Requirements

Java 25. The tests need nothing else: `./gradlew build javadoc`.

The comparisons against real files and `fpcalc`'s output (`Mp3CorpusComparisonTest`, `Mp3FingerprintComparisonTest`)
read their inputs from environment variables, and are skipped when those aren't set. They're not in git: they stay local.
