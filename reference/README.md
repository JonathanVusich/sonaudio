# Reference environment

The decoders in sonaudio have to match FFmpeg's output, and its fingerprints `fpcalc`'s. This directory builds the
FFmpeg and `fpcalc` that every reference comes from, so the host's own ffmpeg is never used.

## The image

`Dockerfile` builds `sonaudio-ref` from source on Debian trixie. Everything is pinned and checked against its SHA-256:

| Component   | Version | Source                                                      | SHA-256                                                            |
| ----------- | ------- | ----------------------------------------------------------- | ------------------------------------------------------------------ |
| FFmpeg      | 9.0.2   | `https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz` (tag n9.0.2) | `8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e` |
| libopus     | 1.6.1   | `https://downloads.xiph.org/releases/opus/opus-1.6.1.tar.gz` | `6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1` |
| Chromaprint | 1.6.2   | `https://github.com/acoustid/chromaprint/releases/download/v1.6.2/chromaprint-1.6.2.tar.gz` | `d54ada62c16b2db396d3251ec54fc7a58909c163131a1cfcf7fa6407dbc14360` |

FFmpeg 9.0.2 was the newest stable release on ffmpeg.org when this was written (8.1.3, a later release of an older
branch, is not newer), and Chromaprint 1.6.2 the newest on GitHub. The FFmpeg is a static build with the default
decoders and muxers, so it has the native `alac` and `opus` decoders, the `alac` encoder, `libopus`, and the mov/ipod,
ogg, matroska, wav and flac formats. `fpcalc` links the same FFmpeg libraries, so it decodes with the same decoders.
Chromaprint's FFT is its default for FFmpeg 9, libavutil's tx. (The fingerprints in `FingerprintTest` are those of
Chromaprint 1.5.1.) `opusinfo` and mutagen are there too, to inspect and to tag.

The native `opus` decoder, not `libopus`, is what FFmpeg uses for Opus: `ffmpeg -decoders` lists both, and decoding an
Opus file prints `Stream #0:0 -> #0:0 (opus (native) -> pcm_s16le (native))`.

The scripts build the image themselves, the first time and when the Dockerfile changes (the image carries the
Dockerfile's hash as a label). With Docker Desktop on Windows, run them from Git Bash.

## Scripts

- `make-fixtures.sh` makes the committed ALAC and Opus fixtures and the fingerprint references under
  `src/test/resources/dev/javax/sonaudio/` again, byte for byte. `fixtures.sh` is what runs in the image;
  `gen-chords.py` writes the fingerprint audio; `tag-fixtures.py` writes the tags FFmpeg can't.
  `inspect-opus.py` and `inspect-alac.py` read what a file really contains (the TOC bytes of each Opus packet, the
  uncompressed frames of an ALAC file).
- `prepare-fate.sh [dir]` does everything for the FATE tests: `fetch-fate-samples.sh` downloads the samples (checked
  against `fate-samples.manifest`), and `make-fate-references.sh` (running `fate-references.sh` in the image) writes
  `<dir>/sonaudio/`: the Opus files remuxed to Ogg, FFmpeg's decodes, FATE's ALAC encodes, `inside.m4a`'s decode MD5
  (checked against FATE's) and `fpcalc`'s output. The default directory is `.fate-samples/` at the project root, which
  git ignores. Nothing present and up to date is fetched or made again; the references are made again when the
  Dockerfile, `fate-references.sh` or the manifest change.
  `FateSamples` (in the tests) reads the directory from `FATE_SAMPLES`, or `.fate-samples/`, and skips the tests when
  it isn't prepared.
