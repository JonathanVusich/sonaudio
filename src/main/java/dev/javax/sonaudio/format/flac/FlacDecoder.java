package dev.javax.sonaudio.format.flac;

import dev.javax.bitstream.BitInputStream;
import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.IntPcmAudio;
import dev.javax.sonaudio.format.IntPcmFrame;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.NoSuchElementException;

/**
 * Decodes the audio frames of a FLAC stream to PCM samples (RFC 9639 section 9), a frame at a time as each is asked
 * for, so callers that only need the start of the audio can stop early. {@link FlacParser#parse} opens one, in its
 * {@link ParsedFlac}. It reads each frame's bits most significant first, and keeps the CRC-8 of the frame header and
 * the CRC-16 of the whole frame (RFC 9639 sections 9.1.8 and 9.3) over the bytes read since the frame started.
 *
 * <p>{@link #nextFrame} throws an {@link EOFException} when the stream ends before a frame does.
 */
public final class FlacDecoder implements IntPcmAudio {

    private static final int SYNC_CODE = 0b11111111111110;

    // The channel assignment codes from which a frame's two channels are decorrelated. Those below code one channel
    // more than the code each, on its own, and those above are reserved.
    private static final int LEFT_SIDE_CODE = 8;
    private static final int SIDE_RIGHT_CODE = 9;
    private static final int MID_SIDE_CODE = 10;
    private static final int DECORRELATED_CHANNELS = 2;

    private final StreamInfo streamInfo;
    private final Crc crc8 = new Crc(0x07, 8);
    private final Crc crc16 = new Crc(0x8005, 16);
    private final ChecksummedByteSource bytes;
    private final BitInputStream bits;
    private long samplesDecoded;

    /**
     * @param inputStream left at the first audio frame
     */
    FlacDecoder(final StreamInfo streamInfo, final InputStream inputStream) {
        this.streamInfo = streamInfo;
        this.bytes = new ChecksummedByteSource(inputStream, crc8, crc16);
        this.bits = BitInputStream.wrap(bytes, ByteOrder.BIG_ENDIAN);
    }

    /**
     * @return the stream's STREAMINFO block, which its frames are decoded by
     */
    public StreamInfo streamInfo() {
        return streamInfo;
    }

    @Override
    public int sampleRate() {
        return streamInfo.sampleRate();
    }

    @Override
    public int bitsPerSample() {
        return streamInfo.bitsPerSample();
    }

    @Override
    public IntPcmFrame nextFrame() throws IOException, InvalidFormatException {
        if (!hasNextFrame()) {
            throw new NoSuchElementException("FLAC stream has no more frames!");
        }
        return readFrame();
    }

    @Override
    public boolean hasNextFrame() throws IOException {
        // A stream may end with other data, like an ID3v1 tag, so stop at the length STREAMINFO gives when it has one.
        final var totalSamples = streamInfo.interChannelSamples();
        if (totalSamples > 0) {
            return samplesDecoded < totalSamples;
        }
        // Without a length, the stream ends where the next frame would start. Anywhere else, it's cut short.
        return bytes.hasMoreBytes();
    }

    // The CRCs start over with each frame.
    private IntPcmFrame readFrame() throws IOException, InvalidFormatException {
        crc8.reset();
        crc16.reset();
        final var header = readFrameHeader();
        final var channels = readSubframes(header);
        readFrameFooter();
        samplesDecoded += header.blockSize();
        final var samples = decorrelate(header.channelAssignment(), channels);
        return new IntPcmFrame(samples, streamInfo.sampleRate());
    }

    private long[][] readSubframes(final FrameHeader header) throws IOException, InvalidFormatException {
        final var channels = new long[header.channelCount()][];
        for (var channel = 0; channel < channels.length; channel++) {
            channels[channel] = readSubframe(header.blockSize(), header.subframeBitsPerSample(channel));
        }
        return channels;
    }

    // The subframes are padded to whole bytes, and the frame ends with the CRC-16 of all of it.
    private void readFrameFooter() throws IOException, InvalidFormatException {
        bits.alignToByte();
        bits.readBits(16);
        if (crc16.getValue() != 0) {
            throw new InvalidFormatException("FLAC frame fails its CRC-16 check!");
        }
    }

    private FrameHeader readFrameHeader() throws IOException, InvalidFormatException {
        readSyncCode();
        // The blocking strategy only says how the coded number below counts.
        bits.readBits(1);
        final var blockSizeCode = (int) bits.readBits(4);
        final var sampleRateCode = (int) bits.readBits(4);
        final var channelCode = readChannelCode();
        final var bitDepthCode = readBitDepthCode();
        skipCodedNumber();
        final var blockSize = readBlockSize(blockSizeCode);
        final var sampleRate = readSampleRate(sampleRateCode);
        readHeaderCrc();
        final var bitsPerSample = bitDepth(bitDepthCode);
        final var header =
                new FrameHeader(blockSize, channelAssignment(channelCode), channelCount(channelCode), bitsPerSample);
        checkStreamInfo(header, sampleRate);
        return header;
    }

    private void readSyncCode() throws IOException, InvalidFormatException {
        if (bits.readBits(14) != SYNC_CODE || bits.readBits(1) != 0) {
            throw new InvalidFormatException("FLAC frame doesn't start with a frame header!");
        }
    }

    private int readChannelCode() throws IOException, InvalidFormatException {
        final var channelCode = (int) bits.readBits(4);
        if (channelCode > MID_SIDE_CODE) {
            throw new InvalidFormatException("FLAC frame header has a reserved channel assignment!");
        }
        return channelCode;
    }

    private ChannelAssignment channelAssignment(final int channelCode) {
        return switch (channelCode) {
            case LEFT_SIDE_CODE -> ChannelAssignment.LEFT_SIDE;
            case SIDE_RIGHT_CODE -> ChannelAssignment.SIDE_RIGHT;
            case MID_SIDE_CODE -> ChannelAssignment.MID_SIDE;
            default -> ChannelAssignment.INDEPENDENT;
        };
    }

    private int channelCount(final int channelCode) {
        if (channelCode < LEFT_SIDE_CODE) {
            return channelCode + 1;
        }
        return DECORRELATED_CHANNELS;
    }

    // The bit depth's code is followed by a reserved bit.
    private int readBitDepthCode() throws IOException, InvalidFormatException {
        final var bitDepthCode = (int) bits.readBits(3);
        if (bits.readBits(1) != 0) {
            throw new InvalidFormatException("FLAC frame header has its reserved bit set!");
        }
        return bitDepthCode;
    }

    private void readHeaderCrc() throws IOException, InvalidFormatException {
        bits.readBits(8);
        if (crc8.getValue() != 0) {
            throw new InvalidFormatException("FLAC frame header fails its CRC-8 check!");
        }
    }

    // sonaudio takes all three from STREAMINFO to size what it decodes, so a frame that disagrees means one is corrupt.
    private void checkStreamInfo(final FrameHeader header, final int sampleRate) throws InvalidFormatException {
        if (sampleRate != streamInfo.sampleRate()
                || header.channelCount() != streamInfo.numberOfChannels()
                || header.bitsPerSample() != streamInfo.bitsPerSample()) {
            throw new InvalidFormatException("FLAC frame header doesn't match STREAMINFO!");
        }
    }

    // RFC 9639 section 9.1.2.
    private int readSampleRate(final int code) throws IOException, InvalidFormatException {
        return switch (code) {
            case 0 -> streamInfo.sampleRate();
            case 1 -> 88200;
            case 2 -> 176400;
            case 3 -> 192000;
            case 4 -> 8000;
            case 5 -> 16000;
            case 6 -> 22050;
            case 7 -> 24000;
            case 8 -> 32000;
            case 9 -> 44100;
            case 10 -> 48000;
            case 11 -> 96000;
            case 12 -> (int) bits.readBits(8) * 1000;
            case 13 -> (int) bits.readBits(16);
            case 14 -> (int) bits.readBits(16) * 10;
            default -> throw new InvalidFormatException("FLAC frame header has an invalid sample rate!");
        };
    }

    // The frame or sample number, coded like UTF-8 in up to seven bytes (RFC 9639 section 9.1.5).
    private void skipCodedNumber() throws IOException, InvalidFormatException {
        final var first = (int) bits.readBits(8);
        final var length = Integer.numberOfLeadingZeros(~first << 24);
        if (length == 1 || length > 7) {
            throw new InvalidFormatException("FLAC frame header has an invalid coded number!");
        }
        for (var i = 1; i < length; i++) {
            if (bits.readBits(8) >> 6 != 0b10) {
                throw new InvalidFormatException("FLAC frame header has an invalid coded number!");
            }
        }
    }

    private int readBlockSize(final int code) throws IOException, InvalidFormatException {
        return switch (code) {
            case 0 -> throw new InvalidFormatException("FLAC frame header has a reserved block size!");
            case 1 -> 192;
            case 2, 3, 4, 5 -> 576 << (code - 2);
            case 6 -> (int) bits.readBits(8) + 1;
            case 7 -> (int) bits.readBits(16) + 1;
            default -> 256 << (code - 8);
        };
    }

    private int bitDepth(final int code) throws InvalidFormatException {
        return switch (code) {
            case 0 -> streamInfo.bitsPerSample();
            case 1 -> 8;
            case 2 -> 12;
            case 4 -> 16;
            case 5 -> 20;
            case 6 -> 24;
            case 7 -> 32;
            default -> throw new InvalidFormatException("FLAC frame header has a reserved bit depth!");
        };
    }

    // RFC 9639 section 9.2.
    private long[] readSubframe(final int blockSize, final int frameBitsPerSample)
            throws IOException, InvalidFormatException {
        if (bits.readBits(1) != 0) {
            throw new InvalidFormatException("FLAC subframe header doesn't start with a 0 bit!");
        }
        final var type = (int) bits.readBits(6);
        final var wastedBits = readWastedBits();
        final var bitsPerSample = frameBitsPerSample - wastedBits;
        if (bitsPerSample < 1) {
            throw new InvalidFormatException("FLAC subframe has more wasted bits than bits per sample!");
        }
        final var samples = readSubframeSamples(type, blockSize, bitsPerSample);
        for (var i = 0; i < samples.length; i++) {
            samples[i] <<= wastedBits;
        }
        return samples;
    }

    // A flag says whether the samples have wasted bits, whose count follows in unary, less one.
    private int readWastedBits() throws IOException {
        if (bits.readBits(1) == 0) {
            return 0;
        }
        return readUnary() + 1;
    }

    private long[] readSubframeSamples(final int type, final int blockSize, final int bitsPerSample)
            throws IOException, InvalidFormatException {
        if (type == 0) {
            return readConstant(blockSize, bitsPerSample);
        }
        if (type == 1) {
            return readWarmUp(blockSize, blockSize, bitsPerSample);
        }
        if (type >= 0b001000 && type <= 0b001100) {
            return readFixed(blockSize, type - 0b001000, bitsPerSample);
        }
        if (type >= 0b100000) {
            return readLinearPredictor(blockSize, type - 0b011111, bitsPerSample);
        }
        throw new InvalidFormatException("FLAC subframe has a reserved type: " + type);
    }

    private long[] readConstant(final int blockSize, final int bitsPerSample) throws IOException {
        final var samples = new long[blockSize];
        final var value = readSigned(bitsPerSample);
        Arrays.fill(samples, value);
        return samples;
    }

    // RFC 9639 section 9.2.5.
    private long[] readFixed(final int blockSize, final int order, final int bitsPerSample)
            throws IOException, InvalidFormatException {
        final var warmUp = readWarmUp(blockSize, order, bitsPerSample);
        final var samples = readResidual(blockSize, order);
        System.arraycopy(warmUp, 0, samples, 0, order);
        for (var i = order; i < samples.length; i++) {
            samples[i] += fixedPrediction(samples, order, i);
        }
        return samples;
    }

    private long fixedPrediction(final long[] samples, final int order, final int index) {
        return switch (order) {
            case 0 -> 0;
            case 1 -> samples[index - 1];
            case 2 -> 2 * samples[index - 1] - samples[index - 2];
            case 3 -> 3 * samples[index - 1] - 3 * samples[index - 2] + samples[index - 3];
            default -> 4 * samples[index - 1] - 6 * samples[index - 2] + 4 * samples[index - 3] - samples[index - 4];
        };
    }

    // RFC 9639 section 9.2.6.
    private long[] readLinearPredictor(final int blockSize, final int order, final int bitsPerSample)
            throws IOException, InvalidFormatException {
        final var warmUp = readWarmUp(blockSize, order, bitsPerSample);
        final var precision = readCoefficientPrecision();
        final var shift = readPredictionShift();
        final var coefficients = readCoefficients(order, precision);
        final var samples = readResidual(blockSize, order);
        System.arraycopy(warmUp, 0, samples, 0, order);
        for (var i = order; i < samples.length; i++) {
            samples[i] += linearPrediction(samples, coefficients, i) >> shift;
        }
        return samples;
    }

    private int readCoefficientPrecision() throws IOException, InvalidFormatException {
        final var precision = (int) bits.readBits(4) + 1;
        if (precision == 16) {
            throw new InvalidFormatException("FLAC subframe has an invalid coefficient precision!");
        }
        return precision;
    }

    private int readPredictionShift() throws IOException, InvalidFormatException {
        final var shift = (int) readSigned(5);
        if (shift < 0) {
            throw new InvalidFormatException("FLAC subframe has a negative prediction shift!");
        }
        return shift;
    }

    private long[] readCoefficients(final int order, final int precision) throws IOException {
        final var coefficients = new long[order];
        for (var j = 0; j < order; j++) {
            coefficients[j] = readSigned(precision);
        }
        return coefficients;
    }

    private long linearPrediction(final long[] samples, final long[] coefficients, final int index) {
        var prediction = 0L;
        for (var j = 0; j < coefficients.length; j++) {
            prediction += coefficients[j] * samples[index - 1 - j];
        }
        return prediction;
    }

    // Reads the samples a predictor starts from, stored as they are; a verbatim subframe stores every sample so.
    private long[] readWarmUp(final int blockSize, final int order, final int bitsPerSample)
            throws IOException, InvalidFormatException {
        if (order > blockSize) {
            throw new InvalidFormatException("FLAC subframe's predictor order is larger than its block!");
        }
        final var samples = new long[order];
        for (var i = 0; i < order; i++) {
            samples[i] = readSigned(bitsPerSample);
        }
        return samples;
    }

    // Reads the coded residual (RFC 9639 section 9.2.7), which follows the warm-up samples, so the first `order`
    // values are left 0.
    private long[] readResidual(final int blockSize, final int order) throws IOException, InvalidFormatException {
        final var parameterBits = readParameterBits();
        final var partitionOrder = (int) bits.readBits(4);
        final var partitionSize = blockSize >> partitionOrder;
        if (partitionSize << partitionOrder != blockSize || partitionSize < order) {
            throw new InvalidFormatException("FLAC subframe's residual partitions don't fit its block!");
        }
        final var residual = new long[blockSize];
        var start = order;
        for (var partition = 0; partition < 1 << partitionOrder; partition++) {
            final var end = (partition + 1) * partitionSize;
            readPartition(residual, start, end, parameterBits);
            start = end;
        }
        return residual;
    }

    private int readParameterBits() throws IOException, InvalidFormatException {
        return switch ((int) bits.readBits(2)) {
            case 0 -> 4;
            case 1 -> 5;
            default -> throw new InvalidFormatException("FLAC subframe has a reserved residual coding method!");
        };
    }

    // A partition's Rice parameter, or the escape code and the bits each value takes, then its values.
    private void readPartition(final long[] residual, final int start, final int end, final int parameterBits)
            throws IOException {
        final var escape = (1 << parameterBits) - 1;
        final var parameter = (int) bits.readBits(parameterBits);
        if (parameter == escape) {
            final var valueBits = (int) bits.readBits(5);
            for (var i = start; i < end; i++) {
                residual[i] = readSignedOrZero(valueBits);
            }
            return;
        }
        for (var i = start; i < end; i++) {
            residual[i] = readRiceValue(parameter);
        }
    }

    private long readRiceValue(final int parameter) throws IOException {
        final var quotient = (long) readUnary() << parameter;
        final var folded = quotient | readBitsOrZero(parameter);
        return (folded >>> 1) ^ -(folded & 1);
    }

    // Undoes the stereo decorrelation of RFC 9639 section 4.2.
    private int[][] decorrelate(final ChannelAssignment channelAssignment, final long[][] channels) {
        return switch (channelAssignment) {
            case INDEPENDENT -> independentChannels(channels);
            case LEFT_SIDE -> undoLeftSide(channels);
            case SIDE_RIGHT -> undoSideRight(channels);
            case MID_SIDE -> undoMidSide(channels);
        };
    }

    private int[][] undoLeftSide(final long[][] channels) {
        final var samples = new int[2][channels[0].length];
        for (var i = 0; i < samples[0].length; i++) {
            samples[0][i] = (int) channels[0][i];
            samples[1][i] = (int) (channels[0][i] - channels[1][i]);
        }
        return samples;
    }

    private int[][] undoSideRight(final long[][] channels) {
        final var samples = new int[2][channels[0].length];
        for (var i = 0; i < samples[0].length; i++) {
            samples[0][i] = (int) (channels[0][i] + channels[1][i]);
            samples[1][i] = (int) channels[1][i];
        }
        return samples;
    }

    private int[][] undoMidSide(final long[][] channels) {
        final var samples = new int[2][channels[0].length];
        for (var i = 0; i < samples[0].length; i++) {
            final var side = channels[1][i];
            final var mid = (channels[0][i] << 1) | (side & 1);
            samples[0][i] = (int) ((mid + side) >> 1);
            samples[1][i] = (int) ((mid - side) >> 1);
        }
        return samples;
    }

    private int[][] independentChannels(final long[][] channels) {
        final var samples = new int[channels.length][channels[0].length];
        for (var channel = 0; channel < samples.length; channel++) {
            for (var i = 0; i < samples[channel].length; i++) {
                samples[channel][i] = (int) channels[channel][i];
            }
        }
        return samples;
    }

    /**
     * @param count from 1 to 63
     */
    private long readSigned(final int count) throws IOException {
        final var shift = 64 - count;
        return (bits.readBits(count) << shift) >> shift;
    }

    // The bit stream can't read 0 bits, which stand for a value of 0.
    private long readSignedOrZero(final int count) throws IOException {
        if (count == 0) {
            return 0;
        }
        return readSigned(count);
    }

    private long readBitsOrZero(final int count) throws IOException {
        if (count == 0) {
            return 0;
        }
        return bits.readBits(count);
    }

    /**
     * @return the number of 0 bits before the next 1 bit, which is read too
     */
    private int readUnary() throws IOException {
        final var zeros = bits.countZeros();
        // The count stops at the end of the input too, where reading the 1 bit throws.
        bits.readBits(1);
        return zeros;
    }

    private record FrameHeader(
            int blockSize, ChannelAssignment channelAssignment, int channelCount, int bitsPerSample) {

        // The side channel has one bit more than the others, as it's a difference of two of them.
        int subframeBitsPerSample(final int channel) {
            if (isSide(channel)) {
                return bitsPerSample + 1;
            }
            return bitsPerSample;
        }

        private boolean isSide(final int channel) {
            return switch (channelAssignment) {
                case INDEPENDENT -> false;
                case LEFT_SIDE, MID_SIDE -> channel == 1;
                case SIDE_RIGHT -> channel == 0;
            };
        }
    }
}
