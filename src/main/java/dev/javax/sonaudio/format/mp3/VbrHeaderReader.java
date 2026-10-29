package dev.javax.sonaudio.format.mp3;

import dev.javax.bitstream.BitInputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import dev.javax.sonaudio.format.mp3.FirstFrame.AudioFrame;
import dev.javax.sonaudio.format.mp3.FirstFrame.VbrHeader;

/**
 * Reads the VBR header an encoder may put in an MP3's first frame: a Xing header (written as Info at a constant
 * bitrate), which LAME and FFmpeg follow with a LAME tag, or Fraunhofer's VBRI header.
 */
final class VbrHeaderReader {

    // "Xing", "Info" and "VBRI".
    private static final long XING = 0x58_69_6E_67;
    private static final long INFO = 0x49_6E_66_6F;
    private static final long VBRI = 0x56_42_52_49;
    private static final int ID_SIZE = 4;

    // Xing's flags say which of its fields are present, in this order.
    private static final int FRAMES_FLAG = 0x1;
    private static final int BYTES_FLAG = 0x2;
    private static final int TOC_FLAG = 0x4;
    private static final int QUALITY_FLAG = 0x8;
    private static final int TOC_SIZE = 100;

    // VBRI always starts 32 bytes after the frame header, and its version, which FFmpeg only reads when 1, is followed
    // by its delay, quality, byte count and frame count.
    private static final int VBRI_OFFSET = FrameHeader.SIZE + 32;
    private static final int VBRI_VERSION = 1;
    private static final int VBRI_DELAY_AND_QUALITY_SIZE = 4;

    // The encoders whose Xing headers a LAME tag follows, as FFmpeg checks for them: "LAME", "Lavf" and "Lavc".
    private static final long LAME = 0x4C_41_4D_45;
    private static final long LAVF = 0x4C_61_76_66;
    private static final long LAVC = 0x4C_61_76_63;
    // The tag packs the delay and padding into three bytes, twelve bits each, after the encoder and 17 more bytes.
    private static final int LAME_DELAY_OFFSET = 17;

    /**
     * Reads the first frame up to the end of its VBR header.
     *
     * @param frame the frame after its header, which ends where the frame does
     */
    FirstFrame readFirstFrame(final FrameHeader header, final BoundedInputStream frame) throws IOException {
        final var bits = BitInputStream.wrap(frame, ByteOrder.BIG_ENDIAN);
        // FFmpeg and mpg123 look for a Xing header straight after the side information, whether or not the frame has
        // a CRC.
        if (!(readId(frame, bits, header.sideInfoSize()) instanceof Id id)) {
            return readVbriHeaderAtOffset(header, frame, bits);
        }
        if (id.value() == XING || id.value() == INFO) {
            return readXingHeader(frame, bits);
        }
        return readVbriHeader(header, frame, bits, id.value());
    }

    // Its frame and byte counts are each optional. Without either, the frames are counted instead, taking this one for
    // audio, which it is: a frame of silence.
    private static FirstFrame readXingHeader(final BoundedInputStream frame, final BitInputStream bits)
            throws IOException {
        final var flags = bits.readBits(32);
        final var frameCount = readField(bits, flags, FRAMES_FLAG);
        final var byteCount = readField(bits, flags, BYTES_FLAG);
        if (frameCount == 0 && byteCount == 0) {
            return new AudioFrame();
        }
        frame.skipNBytes(xingFieldsSize(flags));
        return readLameTag(frame, bits, frameCount, byteCount);
    }

    private static long readField(final BitInputStream bits, final long flags, final int flag) throws IOException {
        if ((flags & flag) == 0) {
            return 0;
        }
        return bits.readBits(32);
    }

    // The fields after the frame and byte counts.
    private static int xingFieldsSize(final long flags) {
        var size = 0;
        if ((flags & TOC_FLAG) != 0) {
            size += TOC_SIZE;
        }
        if ((flags & QUALITY_FLAG) != 0) {
            size += Integer.BYTES;
        }
        return size;
    }

    // Other encoders may leave the rest of the frame empty, or fill it with their own data.
    private static VbrHeader readLameTag(
            final BoundedInputStream frame, final BitInputStream bits, final long frameCount, final long byteCount)
            throws IOException {
        if (!(readId(frame, bits, 0) instanceof Id encoder) || !isLameEncoder(encoder.value())) {
            return new VbrHeader(frameCount, byteCount, new EncoderTrim.None());
        }
        frame.skipNBytes(LAME_DELAY_OFFSET);
        final var delay = (int) bits.readBits(12);
        final var padding = (int) bits.readBits(12);
        return new VbrHeader(frameCount, byteCount, new EncoderTrim.Lame(delay, padding));
    }

    // In MPEG-1 stereo, the Xing header's place is VBRI's too, so the ID read there is VBRI's.
    private static FirstFrame readVbriHeader(
            final FrameHeader header, final BoundedInputStream frame, final BitInputStream bits, final long xingId)
            throws IOException {
        if (vbriGap(header) < 0) {
            return readVbriFields(frame, bits, xingId);
        }
        return readVbriHeaderAtOffset(header, frame, bits);
    }

    // VBRI is read from where its offset is, when the frame has bytes enough.
    private static FirstFrame readVbriHeaderAtOffset(
            final FrameHeader header, final BoundedInputStream frame, final BitInputStream bits) throws IOException {
        final var gap = vbriGap(header);
        if (gap < 0 || !(readId(frame, bits, gap) instanceof Id id)) {
            return new AudioFrame();
        }
        return readVbriFields(frame, bits, id.value());
    }

    private static int vbriGap(final FrameHeader header) {
        return VBRI_OFFSET - (FrameHeader.SIZE + header.sideInfoSize() + ID_SIZE);
    }

    private static FirstFrame readVbriFields(final BoundedInputStream frame, final BitInputStream bits, final long id)
            throws IOException {
        if (id != VBRI || bits.readBits(16) != VBRI_VERSION) {
            return new AudioFrame();
        }
        frame.skipNBytes(VBRI_DELAY_AND_QUALITY_SIZE);
        final var byteCount = bits.readBits(32);
        return new VbrHeader(bits.readBits(32), byteCount, new EncoderTrim.None());
    }

    private static boolean isLameEncoder(final long encoder) {
        return encoder == LAME || encoder == LAVF || encoder == LAVC;
    }

    // The ID after skipping that many bytes, which a short frame of audio may not have.
    private static IdRead readId(final BoundedInputStream frame, final BitInputStream bits, final int skip)
            throws IOException {
        if (frame.getRemaining() < skip + ID_SIZE) {
            return new NoId();
        }
        frame.skipNBytes(skip);
        return new Id(bits.readBits(32));
    }

    private sealed interface IdRead permits Id, NoId {}

    private record Id(long value) implements IdRead {}

    private record NoId() implements IdRead {}
}
