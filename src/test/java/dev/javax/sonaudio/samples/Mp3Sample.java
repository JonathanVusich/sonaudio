package dev.javax.sonaudio.samples;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.SeekableByteChannel;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

/**
 * One-second 440 Hz tones that FFmpeg 4.2 encoded with LAME and tagged, and files derived from them for what no tool
 * at hand writes. The derived ones were written by a one-off script, to the byte layouts in the ID3v2 and MPEG audio
 * specs, and FFmpeg reads back the tags and frames it can (ID3v2.2, and ID3v2.4's compressed and unsynchronised
 * frames).
 *
 * <p>Then the Layer III streams of the ISO/IEC 11172-4 conformance tests, as FFmpeg's test suite has them
 * (fate-suite.ffmpeg.org/mp3-conformance), which cover what LAME never writes, and half-second files that FFmpeg 4.2
 * encoded with LAME and shine from tones with sharp attacks, which LAME codes in short blocks.
 */
public enum Mp3Sample {
    // 44.1 kHz joint stereo at 128 kbit/s, with an Info header and LAME tag, and an ID3v2.4 tag.
    STEREO_CBR_ID3V24("stereo_cbr_id3v24.mp3"),
    // 22.05 kHz (MPEG-2) mono at a variable bitrate, with a Xing header and LAME tag, an ID3v2.3 tag and an ID3v1 tag.
    MONO_VBR_ID3V23("mono_vbr_id3v23.mp3"),
    // 44.1 kHz joint stereo at 128 kbit/s, with no VBR header and only an ID3v1 tag. FFmpeg only writes ID3v1 tags
    // with ID3v2 ones, so the ID3v2 tag was cut from the start.
    NO_XING_ID3V1("no_xing_id3v1.mp3"),
    // NO_XING_ID3V1 with the bitrate index of every frame header cleared, so its frames are free format.
    FREE_FORMAT("free_format.mp3"),
    // The first five frames of NO_XING_ID3V1 after an ID3v2.2 tag, which has a UTF-16 artist, TXX and UFI frames.
    ID3V22("id3v22.mp3"),
    // The same frames after an ID3v2.3 tag whose TIT2 frame is compressed.
    COMPRESSED_ID3V23("compressed_id3v23.mp3"),
    // The same frames after an ID3v2.4 tag whose TIT2 frame is compressed, and whose TPE1 frame is unsynchronised.
    COMPRESSED_ID3V24("compressed_id3v24.mp3"),
    // The same frames between an ID3v2.4 tag with a title and an artist, and an appended ID3v2.4 tag with a title,
    // followed by an ID3v1 tag with a title and an album.
    APPENDED_ID3V24("appended_id3v24.mp3"),
    // The conformance streams. These have their reference decoder's output, which plays them from their first sample.
    // MPEG-1 mono at 32 and 48 kHz, going through every bitrate from 32 to 320 kbit/s.
    HE_32KHZ("he_32khz.mp3"),
    HE_48KHZ("he_48khz.mp3"),
    // MPEG-1 stereo at 44.1 kHz in free format, of about 120 kbit/s.
    HE_FREE("he_free.mp3"),
    // MPEG-1 stereo at 44.1 kHz and 128 kbit/s.
    HECOMMON("hecommon.mp3"),
    // MPEG-1 mono at 44.1 kHz and 64 kbit/s: side information of many kinds, block switching with every block type
    // with and without the mixed block flag, and every Huffman table there is.
    SI("si.mp3"),
    SI_BLOCK("si_block.mp3"),
    SI_HUFF("si_huff.mp3"),
    // MPEG-1 at 44.1 kHz that goes from mono to dual channel, stereo, and joint stereo with every mode extension
    // (intensity stereo among them, in long, short and mixed blocks), then back to mono. Its reference is FFmpeg's
    // decoder's, each frame with its own channels: ISO's reference decoder splits intensity coded mixed blocks
    // differently, and Sona decodes as FFmpeg does, as AcoustID's fpcalc decodes with it.
    HE_MODE("he_mode.mp3"),
    // Tones with sharp attacks, which LAME codes in short blocks, with mid/side stereo: MPEG-1 at 48 kHz and 192
    // kbit/s, MPEG-2 at 22.05 kHz and 48 kbit/s, and MPEG-2.5 at 11.025 kHz and 32 kbit/s, each with an Info header and
    // LAME tag.
    MPEG1_SHORT_BLOCKS("mpeg1_short_blocks.mp3"),
    MPEG2_SHORT_BLOCKS("mpeg2_short_blocks.mp3"),
    MPEG25_SHORT_BLOCKS("mpeg25_short_blocks.mp3"),
    // MPEG-2's intensity stereo, which no encoder at hand writes, so a one-off script wrote it to the bitstream syntax
    // of ISO/IEC 13818-3: 60 frames of joint stereo at 24 kHz and 48 kbit/s, in intensity stereo alone or with mid/side
    // stereo, in long, start, short, stop and mixed blocks, with both intensity scales. Each frame's values are random
    // 0s and 1s, coded with count1 table B: the left channel's up to a line that changes from frame to frame, and the
    // right channel's only in its lowest lines, so its intensity positions, of 3, 2 and 1 bits, place the rest. FFmpeg
    // decodes it as it decodes any other stream, which is its reference.
    MPEG2_INTENSITY_STEREO("mpeg2_intensity_stereo.mp3"),
    // Two tones in stereo at 32 kHz and 64 kbit/s, by shine, whose Info header FFmpeg follows with a LAME tag of no
    // delay.
    SHINE("shine.mp3"),
    // Layers I and II, from the ISO/IEC 13818-4 conformance tests (standards.iso.org/ittf/PubliclyAvailableStandards),
    // whose reference here is FFmpeg's decoder, as Sona decodes as it does. Those of MPEG-1 code more channels in an
    // extension of their frames, which an MPEG-1 decoder skips, decoding the stereo they're compatible with.
    // MPEG-1 Layer II stereo at 44.1 kHz and 384 kbit/s, with CRCs: allocation table B.2b.
    ISO_TEST01("iso_test01.mp3"),
    // MPEG-1 Layer II stereo at 48 kHz and 256 kbit/s, with CRCs: allocation table B.2a.
    ISO_TEST04("iso_test04.mp3"),
    // MPEG-1 Layer I stereo at 44.1 kHz and 384 kbit/s.
    ISO_TEST22("iso_test22.mp3"),
    // MPEG-2 Layer II at 16 kHz, in stereo and joint stereo with every bound.
    ISO_TEST24("iso_test24.mp3"),
    // MPEG-2 Layer II mono at 24 kHz.
    ISO_TEST30("iso_test30.mp3"),
    // MPEG-2 Layer II dual channel at 24 kHz.
    ISO_TEST31("iso_test31.mp3"),
    // MPEG-2 Layer I at 22.05 kHz, in stereo and joint stereo with every bound.
    ISO_TEST33("iso_test33.mp3"),
    // Half-second tones that FFmpeg 4.2 encoded in Layer II stereo at 64 kbit/s: at 44.1 kHz, allocation table B.2c,
    // and at 32 kHz, table B.2d.
    LAYER2_TABLE_C("layer2_table_c.mp3"),
    LAYER2_TABLE_D("layer2_table_d.mp3"),
    // Damaged files a one-off script made from the samples above, which FFmpeg decodes as it does.
    // NO_XING_ID3V1 with 50 bytes of 0xA5 before its third frame, which FFmpeg's decoder drops, leaving the fourth
    // without the main data it starts in.
    JUNK_BETWEEN_FRAMES("junk_between_frames.mp3"),
    // NO_XING_ID3V1 from its second frame, whose main data starts in the first.
    CUT_AT_SECOND_FRAME("cut_at_second_frame.mp3"),
    // NO_XING_ID3V1 without its last 200 bytes: its ID3v1 tag and the end of its last frame.
    CUT_IN_LAST_FRAME("cut_in_last_frame.mp3"),
    // STEREO_CBR_ID3V24 without its last 2000 bytes, so its Info header counts frames it doesn't have.
    CUT_SHORT_OF_INFO_HEADER("cut_short_of_info_header.mp3"),
    // NO_XING_ID3V1 cut in its last frame, 20 bytes in, which is inside its side information. FFmpeg decodes the frame
    // with the side information it has, then zeros.
    CUT_IN_LAST_FRAME_SIDE_INFO("cut_in_last_frame_side_info.mp3"),
    // Pink and white noise in stereo that FFmpeg 4.2 encoded with LAME at 256 kbit/s, which fills the frames with big
    // values, without a VBR header, cut in its last frame 150 and 300 bytes in. FFmpeg stops decoding values where the
    // frame's data ends, whereas the zeros after it would decode as more.
    NOISE_CUT_IN_LAST_FRAME_150("noise_cut_in_last_frame_150.mp3"),
    NOISE_CUT_IN_LAST_FRAME_300("noise_cut_in_last_frame_300.mp3"),
    // An ID3v2.4 tag, with a title, an artist and a composer, followed by ID3V22 with its own tag, and its frames.
    TWO_ID3V2_TAGS("two_id3v2_tags.mp3");

    private final String fileName;

    Mp3Sample(final String fileName) {
        this.fileName = fileName;
    }

    public InputStream inputStream() {
        return Mp3Sample.class.getResourceAsStream("mp3/" + fileName);
    }

    /**
     * @return what a reference decoder decodes the file to, as 16-bit little-endian samples with each frame's channels
     *     interleaved, for the samples that have it: FFmpeg 4.2's ({@code ffmpeg -i <file> -f s16le <file>.pcm}), or a
     *     conformance stream's own, gzipped
     */
    public InputStream referencePcm() {
        try {
            return new GZIPInputStream(
                    Mp3Sample.class.getResourceAsStream("mp3/" + fileName.replace(".mp3", ".pcm.gz")));
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
