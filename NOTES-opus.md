# Opus hand-off notes (temporary: delete before release)

Written by the agent that built the container, framing, range decoder and decoder skeleton in
`src/main/java/dev/javax/sonaudio/format/opus`. The SILK and CELT agents fill in the classes named under "Plug points"
and touch nothing else in that package, except `RangeDecoder` for a missing primitive (see "Range decoder").

FFmpeg source read: **n9.0.2** (the verified tarball, `reference/Dockerfile`'s sha256), `libavcodec/opus/*`,
`libavcodec/decode.c`, `libavformat/oggdec.c`, `oggparseopus.c`, `libswresample`. All behaviour below was also checked
against the 9.0.2 binary in `sonaudio-ref` (framecrc, ffprobe, s16le sizes).

## What is implemented

- **Ogg**: pages (`OpusParser.readPage`: capture pattern, version, flags, granule, serial, sequence, CRC-32, lacing,
  fragments), packet reassembly across pages (`OggStream`), FFmpeg's start/end trimming (`OggStream`, from
  `oggparseopus.c`), logical stream selection (first stream whose first page starts with `OpusHead`).
- **`OpusParser`**: OpusHead (families 0, 1, 2, 255, FFmpeg's validations), OpusTags through the shared Vorbis reader
  (METADATA_BLOCK_PICTURE, R128_* and REPLAYGAIN_* arrive as ordinary unknown tags, as FLAC's do), the file's sample
  count from the last granule position in the last 65307 bytes (`OpusStreamInfo`, exposed as
  `decoder.streamInfo()`), packet framing (`parsePacket`, every RFC 6716 3.4 rule R1-R7 as `ff_opus_parse_packet`
  applies it, self-delimited for multistream).
- **`RangeDecoder`** (RFC 6716 4.1), tested against a test-side RFC 5.1 encoder (`RangeEncoder` in the tests).
- **`OpusDecoder` / `OpusStream`**: all of `dec.c`'s orchestration: per coded stream the range decoder start, SILK layer,
  resampler hand-off and its delayed-sample bookkeeping, redundancy flag parsing and the 5 ms redundancy frames with
  their fades (`RedundancyFadeUtils`), the hybrid add and the CELT delay FIFO, mode-switch flushes, per-frame error ->
  silence; per packet the multistream split, the equal-duration check, the sync between streams, channel mapping
  (families, copies, silence), output gain, pre-skip and end trimming, `FrameContinuity`.

## Plug points (exact signatures; all package-private, in `format/opus`)

```java
final class SilkDecoder {                 // SILK agent: replace the body of decodeFrame and reset
    static final int MAX_FRAME_SAMPLES = 960;
    SilkDecoder(int outputChannels)       // 1 or 2: how the stream is OUTPUT (the header's), not how a packet codes it
    int decodeFrame(RangeDecoder rangeDecoder, OpusBandwidth bandwidth, ChannelCoding coding,
                    int frameMillis, float[][] output) throws InvalidFormatException
    void reset()
}
final class CeltDecoder {                 // CELT agent: replace the body of decodeFrame and reset
    static final int MAX_FRAME_SAMPLES = 960;
    static final int HYBRID_START_BAND = 17;
    CeltDecoder(int outputChannels)
    void decodeFrame(RangeDecoder rangeDecoder, ChannelCoding coding, int frameSamples, int startBand,
                     int endBand, float[][] output) throws InvalidFormatException
    void reset()
}
final class SilkResampler {               // OWNER: whoever finishes SILK first (nobody's brief had it)
    SilkResampler(int outputChannels)
    ResamplerState state();               // CLOSED / OPEN
    int inputSampleRate();                // 8000, 12000 or 16000 of the open resampler
    void open(OpusBandwidth bandwidth);   // swr_init + feed silk_resample_delay[bandwidth] zeros at the input rate
    int convert(float[][] input, int inputSamples, float[][] output, int outputOffset, int outputCapacity)
    void flush(float[][] output, int outputOffset, int samples)
    void close();
}
```

The full contracts are in the Javadoc of those three files; the short version:

- `SilkDecoder.decodeFrame` reads one Opus frame's SILK layer from the **same** `RangeDecoder` the CELT layer then
  continues on (hybrid), writes `frameMillis * silkRate / 1000` samples at index 0 of each of `output[0..outputChannels)`
  at the SILK rate (8/12/16 kHz), returns that count. Coded stereo + output stereo: L/R after unmixing. Coded mono +
  output stereo: both channels equal. **Coded stereo + output mono: `output[0]` is the MID channel as it leaves the
  decoder, without the side prediction** (that is what FFmpeg's `silk.c` does, not an average). It decodes and drops LBRR
  frames. It must not call `shrink`/`initialise` on the range decoder.
- `CeltDecoder.decodeFrame` writes `frameSamples` samples (120/240/480/960) at index 0 of each output channel at 48 kHz,
  after IMDCT, overlap, post-filter and de-emphasis. Coded stereo + output mono: FFmpeg adds the coefficients and halves
  after the IMDCT (the average); coded mono + output stereo: both channels equal. Raw bits are read from the frame
  end: `rangeDecoder.frameBits()` is the frame size in bits (FFmpeg's `framebits`), `consumeToEnd()` is the silence
  flag's `total_bits += framebits - tell`. Also used for the 240-sample redundancy frames (start band 0, on a second
  `RangeDecoder` over the redundancy bytes).
- `reset()` of both is `ff_silk_flush` / `ff_celt_flush`: forget all inter-frame state. `OpusStream` calls
  `silk.reset()` on every CELT-only frame and `celt.reset()` on every SILK-only frame and around redundancy frames.
- State each keeps between frames is listed in the class Javadoc (SILK: per-channel previous-frame history, last
  coded channels; CELT: band energies of the last two frames, IMDCT overlap, post-filter history/params, de-emphasis
  memory, seed).
- Throwing `InvalidFormatException` is how a frame is declared undecodable: FFmpeg then outputs silence of the frame's
  length (`OpusStream.decodeFrameOrSilence`), which is already implemented. No exception may escape otherwise.

### The stubs
`SilkDecoder`, `CeltDecoder` and `SilkResampler` write **silence** and **consume nothing** from the range decoder.
Consequences you will see until they are real: in SILK-only mode `tell()` stays at 1, so `OpusStream.readRedundancy`
always "finds" a redundancy frame (a garbage path that decodes a stub CELT frame and fades silence: harmless, and it
cannot change a frame's sample count); in hybrid mode the redundancy flag is read from the untouched stream and may
produce `size > frameSize`, which zero-fills the frame, again without changing its length (hybrid has no resampler
delay). The shape test asserts counts and channels only.

## Range decoder (`RangeDecoder`)

Mutable working object, restarted per frame with `initialise(byte[] data, int offset, int size)` (it keeps a reference to the
packet bytes, which the caller leaves alone, because raw bits are read from the END of the frame). Reads past either end
return zeros, never throw. API: `decode(total)` + `update(low, high, total)` (RFC `ec_decode`/`ec_dec_update`),
`decodeIcdf(int[] icdf, int tableBits)` (table = inverse CDF, strictly the RFC's `icdf` tables: falls to 0), `decodeBit(logp)`,
`decodeUniform(size)` (`ec_dec_uint`), `decodeRawBits(count<=32)`, `decodeLaplace(zeroFrequency, decay)`,
`decodeStep(k0)` (CELT stereo angle, probability 3 up to `k0`), `decodeTriangular(qn)` (qn even), `tell()`,
`tellFraction()` (counts raw bits too), `frameBits()`, `consumeToEnd()`, `shrink(newSize)` (cut the redundancy bytes
off; moves the raw-bit end like libopus' `ec_dec_shrink`; FFmpeg instead re-inits its raw reader at `data + size`,
which is the same for valid streams). If CELT needs `ec_dec_cdf`-style (total-first) tables, add a method to
`RangeDecoder` and tell the others; nothing else should change in it.

## Behaviour of the hand-off (shape), measured on the 9.0.2 binary

SILK is resampled to 48 kHz by libswresample (`dec.c` `swr_*`, filter_size 16). The first SILK frame after the resampler
is opened comes out `frame_duration - D` samples long where **D = 24 for narrowband and 0 for mediumband, wideband and
the SILK layer of hybrid** (measured on `silk_{nb,mb,wb}_*` and the hybrid fixtures; frame size does not matter). Later
frames are complete. The held-back D samples come out (a) before the next CELT frame, (b) when SILK changes rate, (c) as
a **separate frame after the last packet's** (FFmpeg's drain). `OpusStream` models this with `SilkResampler`'s stub and
`delayedSamples`; the real resampler must reproduce the same counts (it will if it is swr-equivalent). Verified: every
`OpusSample` and every FATE vector (including those that switch modes: testvector09, 12) has exactly FFmpeg's sample count.

## FFmpeg quirks found / reproduced

1. **Narrowband "24 extra samples"** (the `OpusSample` comment is wrong): the 24 are NOT dropped by the CLI. The first
   narrowband frame is 24 short (624 instead of 648 after the pre-skip) and the drain frame after the last packet gives
   the 24, untrimmed, at the previous frame's timestamp (the muxer only warns about the non-monotonic dts). Total
   still 11520 for the fixtures. fpcalc probably sees the same drain frame.
2. **End trim**: only the packet that crosses the last page's granule position gets an end trim (`oggparseopus.c`
   `end_trimming = min(cur_dts + pre_skip - granule, packet duration)`), and `decode.c` applies it only if it is
   `<=` the samples that packet's frame has after the pre-skip; equal drops the frame. So a narrowband one-packet file
   whose trim exceeds the 24-short frame is not trimmed at all.
3. **Single-page streams** (e.g. `silk_wb_mono_20ms`: all packets on the one EOS page): FFmpeg times the packets from 0 rather
   than from `granule - duration - pre_skip`, i.e. `cur_dts` is not synchronised (`lastpts` unknown on an EOS page).
   Reproduced in `OggStream`.
4. **Output gain applied once per output channel, in order**, so a channel that is a *copy* of an earlier one (mapping
   with a repeated index) is scaled twice. Reproduced (`OpusDecoder.applyGain`). Gain is `10^(Q8/(20*256))` as float,
   applied only when the Q7.8 value != 0.
5. The multistream sync (`min` of the streams' outputs, extra samples buffered) is implemented cleanly
   (waiting + new samples per stream); FFmpeg's own accounting drops samples in the mixed-delay case (it indexes the
   buffered tail relative to the wrong base). No fixture exercises it: tron.6ch's four streams are all CELT.
6. Header: demuxer checks only `size >= 19` and the major version nibble; the decoder checks channels != 0, family 0 <= 2
   channels, family 1 <= 8, family 2 channel count `(n+1)^2 [+2]`, `streams + coupled <= 255`, `coupled <= streams`, every
   mapping entry `255` or `< streams + coupled`. All implemented at parse time (FFmpeg fails at decoder open).
7. Samples of a frame are always output; a packet that fails `ff_opus_parse_packet` (or whose streams disagree in
   duration) is dropped whole and the decoder returns an error: here, `FrameContinuity.AFTER_DECODING_ERROR` on every
   later frame. A frame inside a good packet that fails to decode is zeroed, not an error.
8. `silk-lbrr-mono` and `tron.6ch.tinypkts` decode differently from Ogg than from Matroska (ms timestamps); the shape
   test uses `FateSamples.ffmpegPcmOfOpusFile`.
9. FFmpeg's `ogg_get_length` ends up reading the whole stream and leaves `duration = last granule` (it includes the
   pre-skip: ffprobe says 0.2465 s for the 0.24 s fixtures). `OpusStreamInfo.sampleCount` / `RawMetadata.duration` are
   `granule - pre_skip` instead (the required test: reference length / 48000), 312 samples (6.5 ms) shorter.

## Deliberate differences from FFmpeg (all for malformed input only)

- Strict Ogg: a bad capture pattern, version or CRC, a page cut short, a continuation flag that disagrees with the
  packet before it, an empty packet, or a file that ends inside a packet is an `InvalidFormatException`; FFmpeg resyncs
  or ends quietly. (The last-65307-bytes scan for the final granule position resyncs, as FFmpeg's does.)
- Multiplexed files: the first Opus stream is decoded, other serials' pages are skipped. **Chained** streams (a new BOS
  after audio) throw; FFmpeg decodes them as one stream with new headers.
- A packet of code 3 with nothing after its TOC byte throws from the Ogg layer (FFmpeg: error from the demuxer).
- Audio must start on a fresh page after OpusTags (RFC 7845).
- Missing granule position in the last 64 KB: duration is 0 (FFmpeg: unknown).

## Not implemented / not verified

- SILK, CELT, the SWR resampler: stubs (silence). Hybrid, mode transitions, redundancy and fades are wired but have only
  run on silence; the fade math (`RedundancyFadeUtils`) is unit tested against `ff_celt_window2`'s values.
- The output-gain multiply is untestable on silence (applied to zeros); channel mapping is tested at the
  `OpusStreamInfo.channelSources` level and by channel counts on `surround51` and tron.
- Multistream with streams of different delays (quirk 5) is untested.
- `shrink` semantics vs FFmpeg's `raw_init` on a corrupt redundancy size are unverified.

## Which fixtures exercise what

- **SILK-only**: `OpusSample.SILK_*`, `MULTIFRAME_SILK_80MS`, `OUTPUT_GAIN`, `TAGGED`, `R128_PICTURE` (all SILK WB mono);
  FATE `testvector02` (NB), `03` (MB), `04` (WB), `silk-lbrr-mono` (NB mono, LBRR), `testvector12` (mono, switches
  SILK NB/MB/WB and hybrid SWB), `testvector09` (a few SILK NB stereo packets among CELT).
- **Hybrid**: `OpusSample.HYBRID_*`, `MULTIFRAME_HYBRID_*`; FATE `testvector05` (SWB), `06` (FB), `silk-lbrr` (SWB
  stereo, LBRR), `testvector12`.
- **CELT-only**: `OpusSample.CELT_*`, `MULTIFRAME_CELT_*`, `SURROUND51` (4 streams, 2 coupled); FATE `testvector01`
  (stereo, frames 2.5-20 ms, 1-5 per packet), `07`, `08`, `09`, `10`, `11` (all bandwidths, mono/stereo switching, up to
  6 frames per packet), `tron.6ch.tinypkts` (6 channels, 4 streams, 2.5 ms CELT).
- Mode/bandwidth transitions and redundancy: `testvector12`, `testvector09`, `testvector07`/`08`/`10`.

## Where the FATE tests run

`OpusDecoderShapeTest` and `OpusPacketFramingTest` iterate `FateOpusVector` through `FateSamples`, which skips when no
directory is prepared. Run with `FATE_SAMPLES` set (PowerShell: `$env:FATE_SAMPLES = 'C:\Users\Jonathan\sonaudio-fate-samples'`)
and check the XML under `build/test-results/test` for `skipped="0"`.
