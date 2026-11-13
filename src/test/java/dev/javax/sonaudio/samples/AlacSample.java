package dev.javax.sonaudio.samples;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.SeekableByteChannel;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

/**
 * ALAC files that FFmpeg 9.0.2 encoded in the reference image ({@code reference/Dockerfile}), from synthetic audio
 * that {@code reference/make-fixtures.sh} makes again, byte for byte. Each command below is what
 * {@code reference/fixtures.sh} runs, and ends in {@code -bitexact -fflags +bitexact -map_metadata -1 -f ipod
 * <file>.m4a}.
 *
 * <p>The sources are made by {@code ffmpeg -f lavfi -i "aevalsrc='<channel 0>|<channel 1>|...':c=<layout>:s=<rate>:d=<seconds>"}
 * to a WAV file of the sample format named for each constant. Channel {@code c} (from 0) holds two tones and noise:
 * {@code 0.3*sin(2*PI*(440+110*c)*t)+0.2*sin(2*PI*(1250+97*c)*t)*(0.6+0.4*sin(2*PI*3*t))+N*(2*random(c)-1)}, so the
 * audio isn't predictable, and its length isn't a multiple of 4096, the frame size, so the last frame is short. N is
 * 0.002 for 16-bit sources and 0.0001 for 24-bit ones.
 *
 * <p>The references are FFmpeg's decode as raw little-endian samples, gzipped, at the bit depth of the stream: signed
 * 16-bit ({@code ffmpeg -i <file> -f s16le -}) or signed 24-bit ({@code -f s24le}), with each frame's channels
 * interleaved in FFmpeg's channel order. ALAC is lossless, so the script checks that each decode is also its source's
 * samples. Files made from the same source share a reference. FFmpeg's encoder writes only 16 and 24 bits, and nothing
 * else in the image writes ALAC, so there are no 20-bit or 32-bit samples.
 *
 * <p>Note that {@code -compression_level 0} makes FFmpeg's encoder write every frame uncompressed (the frame header's
 * "not compressed" bit is set), whatever the audio. {@code reference/inspect-alac.py} counts such frames.
 */
public enum AlacSample {
    // 16-bit stereo at 44.1 kHz (0.5 s, 22050 samples: 5 frames and one of 1570), mirroring FATE's fate-alac-16-*
    // cases. Reference: s16le. Level 0: every frame is uncompressed.
    STEREO16_LEVEL0("stereo16"),
    // -c:a alac -compression_level 1
    STEREO16_LEVEL1("stereo16"),
    // -c:a alac -compression_level 2
    STEREO16_LEVEL2("stereo16"),
    // -c:a alac -min_prediction_order 1 -max_prediction_order 30
    STEREO16_LPC_ORDERS("stereo16"),
    // 24-bit stereo at 96 kHz (0.25 s, 24000 samples: 5 frames and one of 3520): s32p in FFmpeg, 24 bits in the stream.
    // Reference: s24le. -c:a alac -compression_level 0, which writes every frame uncompressed.
    STEREO24_LEVEL0("stereo24"),
    // -c:a alac -min_prediction_order 1 -max_prediction_order 30
    STEREO24_LPC_ORDERS("stereo24"),
    // 16-bit mono at 44.1 kHz (0.4 s, 17640 samples), encoded as -c:a alac. Reference: s16le.
    MONO16("mono16"),
    // 16-bit 5.1 at 44.1 kHz (0.2 s, 8820 samples), encoded as -c:a alac, with FFmpeg's layout "5.1" (FL FR FC LFE BL
    // BR).
    // Reference: s16le with the channels in that order, as FFmpeg decodes them.
    SURROUND51_16("surround51_16"),
    // 16-bit mono at 8 kHz (0.8 s, 6400 samples) and at 192 kHz (0.2 s, 38400 samples), encoded as -c:a alac.
    // Reference: s16le.
    MONO16_8KHZ("mono16_8khz"),
    MONO16_192KHZ("mono16_192khz"),
    // 16-bit stereo white noise at 44.1 kHz (0.15 s, 6615 samples: one frame and a short one) that is as loud as the
    // format allows: its sources are two anoisesrc (c=white:a=0.98, seeds 1 and 2) joined with join=inputs=2. It costs
    // more coded than it does as it is, so the encoder writes both its frames uncompressed, which
    // reference/inspect-alac.py confirms (2 of 2 frames) although the compression level is 2 by default. Reference:
    // s16le.
    ESCAPE_FRAMES("escape_frames"),
    // The same 0.15 s of 16-bit stereo audio at 44.1 kHz (6615 samples: one frame and a short one) in three layouts.
    // Reference: s16le. moov after mdat, as FFmpeg writes it without faststart (ftyp, free, mdat, moov).
    MOOV_AFTER_MDAT("short16"),
    // -movflags +faststart: moov before mdat (ftyp, moov, free, mdat).
    MOOV_BEFORE_MDAT("short16"),
    // -movflags +faststart with -metadata title="Test Title" artist="Test Artist" album_artist="Test Album Artist"
    // album="Test Album" track=3/12 disc=1/2 date=2020-05-06 composer="Test Composer", which FFmpeg writes as the
    // atoms (c)nam, (c)ART, aART, (c)alb, trkn, disk, (c)day and (c)wrt. Then reference/tag-fixtures.py (mutagen)
    // added MusicBrainz Picard's freeform atoms ("----", mean com.apple.iTunes): MusicBrainz Track Id, Release Track
    // Id, Album Id, Artist Id, Album Artist Id, Release Group Id, Album Type, Album Status and Album Release Country,
    // and ARTISTS, SCRIPT, LABEL, CATALOGNUMBER, BARCODE and originaldate. The audio is MOOV_BEFORE_MDAT's.
    TAGGED("short16");

    private final String referenceName;

    AlacSample(final String referenceName) {
        this.referenceName = referenceName;
    }

    public InputStream inputStream() {
        return AlacSample.class.getResourceAsStream("alac/" + name().toLowerCase() + ".m4a");
    }

    /**
     * @return what FFmpeg 9.0.2 decodes the file to, as raw little-endian samples at the stream's bit depth, with each
     *     frame's channels interleaved, gzipped
     */
    public InputStream referencePcm() {
        try {
            return new GZIPInputStream(AlacSample.class.getResourceAsStream("alac/" + referenceName + ".pcm.gz"));
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
