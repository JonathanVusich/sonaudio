package dev.javax.sonaudio.samples;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.SeekableByteChannel;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

/**
 * Ogg Opus files that FFmpeg 9.0.2 encoded with libopus 1.6.1 in the reference image ({@code reference/Dockerfile}),
 * which {@code reference/make-fixtures.sh} makes again, byte for byte. Each is 0.24 s (11520 samples at 48 kHz) of
 * synthetic audio, short because their references are raw samples that gzip hardly shrinks. Each command below is what
 * {@code reference/fixtures.sh} runs on a source WAV, and ends in {@code -bitexact -fflags +bitexact -map_metadata -1
 * <file>.opus}; each starts with {@code ffmpeg -i <source> -c:a libopus}.
 *
 * <p>The sources are made by {@code ffmpeg -f lavfi -i "aevalsrc='<channel 0>|<channel 1>':c=<layout>:s=48000:d=0.24"}.
 * "Voice" is, on channel {@code c} (from 0), {@code 0.3*sin(2*PI*(440+110*c)*t)+0.2*sin(2*PI*(1250+97*c)*t)*(0.6+
 * 0.4*sin(2*PI*3*t))+0.01*(2*random(c)-1)}, and "clicks" is {@code 0.25*sin(2*PI*(330+70*c)*t)+0.8*lt(mod(t+0.01*c,
 * 0.11),0.0006)*(2*random(c)-1)}: a tone under a burst of noise every 110 ms, for CELT's transients.
 *
 * <p>The labels are what {@code reference/inspect-opus.py} reads from the TOC byte of every packet (RFC 6716 section
 * 3.1), not what the encoder was asked for: the mode (SILK, hybrid or CELT), the bandwidth (NB, MB, WB, SWB, FB), the
 * duration of a frame, how many frames a packet holds and its code (0: one frame, 1: two of one size, 2: two of
 * different sizes, 3: any number), and whether the TOC says mono or stereo. Where a file says nothing else, all of its
 * packets are as labelled. The pre-skip is 312 samples, except for the lowdelay and 5 ms files that say so.
 *
 * <p>The references are FFmpeg's native Opus decoder's output ({@code ffmpeg -i <file> -f s16le -}, which the stream
 * mapping line names {@code opus (native)}, not libopus), as raw little-endian 16-bit samples at 48 kHz with all of a
 * frame's channels interleaved, gzipped. They start after the pre-skip, end where the Ogg granule position ends the
 * stream (11520 samples), and have the OpusHead output gain applied. FFmpeg's channel order is the file's, which for
 * mapping family 1 is Vorbis's mapped to FFmpeg's. There are no f32le references: they would double the size of the
 * fixtures, which are already about 3 MB with the rest. The first frame of a narrowband SILK stream comes out 24
 * samples short, because the resampler holds them back, and a frame of those 24 follows the last packet (FFmpeg's
 * drain, which has the previous frame's timestamp), so a narrowband file still has 11520 samples in all.
 */
public enum OpusSample {
    // SILK-only, from voice at -application voip with -cutoff 4000, 6000 and 8000, at 20 ms. Mono at -b:a 10k, 12k and
    // 16k, stereo at 16k, 20k and 24k. -frame_duration 20. Only the medium and wideband stereo files have stereo
    // packets (their TOC bytes say so); the narrowband one's say mono, as libopus coded it as mono.
    SILK_NB_MONO_20MS,
    SILK_MB_MONO_20MS,
    SILK_WB_MONO_20MS,
    // Two channels in the header, but mono packets (see above).
    SILK_NB_STEREO_20MS,
    SILK_MB_STEREO_20MS,
    SILK_WB_STEREO_20MS,
    // SILK-only at other frame sizes: narrowband mono (-b:a 10k) and wideband stereo (-b:a 24k) at 10, 40 and 60 ms,
    // with -frame_duration set to it. One frame per packet.
    SILK_NB_MONO_10MS,
    SILK_NB_MONO_40MS,
    SILK_NB_MONO_60MS,
    SILK_WB_STEREO_10MS,
    SILK_WB_STEREO_40MS,
    SILK_WB_STEREO_60MS,
    // Hybrid, from voice at -application voip, with -cutoff 12000 (super-wideband) or 20000 (fullband), -b:a 24k mono
    // and 32k stereo for super-wideband, 28k and 36k for fullband. Mono at 10 and 20 ms, stereo at 20.
    HYBRID_SWB_MONO_10MS,
    HYBRID_SWB_MONO_20MS,
    HYBRID_SWB_STEREO_20MS,
    HYBRID_FB_MONO_10MS,
    HYBRID_FB_MONO_20MS,
    HYBRID_FB_STEREO_20MS,
    // CELT-only fullband, from clicks, at -application lowdelay with -b:a 64k mono and 96k stereo: the frame size (-
    // frame_duration) is 2.5, 5, 10 and 20 ms for mono, and 2.5 and 20 ms for stereo. Their pre-skip is 120 samples.
    CELT_LOWDELAY_MONO_2_5MS,
    CELT_LOWDELAY_MONO_5MS,
    CELT_LOWDELAY_MONO_10MS,
    CELT_LOWDELAY_MONO_20MS,
    CELT_LOWDELAY_STEREO_2_5MS,
    CELT_LOWDELAY_STEREO_20MS,
    // CELT-only fullband from voice at -application audio at high bitrates: mono at 128k with 5 ms (pre-skip 120) and
    // 20 ms frames, and stereo at 192k with 20 ms.
    CELT_AUDIO_MONO_5MS,
    CELT_AUDIO_MONO_20MS,
    CELT_AUDIO_STEREO_20MS,
    // Packets of several frames, in mono, from clicks (the CELT ones) and voice. -application audio -b:a 96k
    // -vbr off -frame_duration 40: CELT fullband, two 20 ms frames in each of 7 packets, which libopus pads into code
    // 3.
    MULTIFRAME_CELT_40MS_CBR,
    // The same with -vbr on: two 20 ms frames of one size in each of 7 packets (code 1).
    MULTIFRAME_CELT_40MS_VBR,
    // -application audio -b:a 96k -frame_duration 120: CELT fullband, six 20 ms frames in each of 3 packets (code 3).
    MULTIFRAME_CELT_120MS,
    // -application voip -cutoff 12000 -b:a 24k -frame_duration 120: hybrid super-wideband, six 20 ms frames in each of
    // 3
    // packets (code 3).
    MULTIFRAME_HYBRID_120MS,
    // The same at -frame_duration 40: hybrid super-wideband, two 20 ms frames of different sizes in each of 7 packets
    // (code 2).
    MULTIFRAME_HYBRID_40MS,
    // -application voip -cutoff 8000 -b:a 16k -frame_duration 80: SILK wideband, two 40 ms frames of different sizes in
    // each of 4 packets (code 2).
    MULTIFRAME_SILK_80MS,
    // SILK_WB_MONO_20MS with the output gain in its OpusHead set to -768 (-3 dB in Q7.8) by FFmpeg's bitstream filter,
    // without re-encoding: -c copy -bsf:a opus_metadata=gain=-768. Its reference is decoded with that gain applied.
    OUTPUT_GAIN,
    // 5.1 from voice (six channels, FFmpeg's "5.1" layout), -application audio -b:a 160k -mapping_family 1: 4 streams,
    // 2 of them coupled, with the channel mapping [0, 4, 1, 2, 3, 5]; its 11 packets are fullband 20 ms. The TOC bytes
    // of the first stream say CELT stereo, but those of the third stream (the centre channel) say hybrid, so the file
    // needs the SILK layer too. The reference has the channels in FFmpeg's order.
    SURROUND51,
    // SILK_WB_MONO_20MS remuxed (-c copy) and tagged by reference/tag-fixtures.py (mutagen) the way MusicBrainz Picard
    // writes Vorbis comments: TITLE, ARTIST, ALBUMARTIST, ALBUM, TRACKNUMBER=3, TOTALTRACKS=12, DISCNUMBER=1,
    // TOTALDISCS=2, DATE=2020-05-06, COMPOSER, MUSICBRAINZ_TRACKID, _RELEASETRACKID, _ALBUMID, _ARTISTID,
    // _ALBUMARTISTID
    // and _RELEASEGROUPID, and RELEASETYPE, RELEASESTATUS and RELEASECOUNTRY.
    TAGGED("silk_wb_mono_20ms"),
    // SILK_WB_MONO_20MS remuxed and tagged with TITLE, ARTIST, R128_TRACK_GAIN=-1536, R128_ALBUM_GAIN=512,
    // REPLAYGAIN_TRACK_GAIN, REPLAYGAIN_TRACK_PEAK and a METADATA_BLOCK_PICTURE: a 16 by 16 JPEG (made by ffmpeg,
    // mjpeg)
    // in a FLAC picture block of type 3 (front cover), as mutagen writes it, in base64.
    R128_PICTURE("silk_wb_mono_20ms");

    private final String referenceName;

    OpusSample() {
        this.referenceName = name().toLowerCase();
    }

    OpusSample(final String referenceName) {
        this.referenceName = referenceName;
    }

    public InputStream inputStream() {
        return OpusSample.class.getResourceAsStream("opus/" + name().toLowerCase() + ".opus");
    }

    /**
     * @return what FFmpeg 9.0.2's native decoder decodes the file to, as 16-bit little-endian samples at 48 kHz with
     *     each frame's channels interleaved ({@code ffmpeg -i <file> -f s16le -}), gzipped
     */
    public InputStream referencePcm() {
        try {
            return new GZIPInputStream(OpusSample.class.getResourceAsStream("opus/" + referenceName + ".pcm.gz"));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @return the file in memory, as parsers read it
     */
    public SeekableByteChannel channel() throws IOException {
        try (final var inputStream = inputStream()) {
            return new SeekableInMemoryByteChannel(inputStream.readAllBytes());
        }
    }
}
