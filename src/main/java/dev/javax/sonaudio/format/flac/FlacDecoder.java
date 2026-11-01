package dev.javax.sonaudio.format.flac;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.NoSuchElementException;
import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.IntPcmAudio;
import dev.javax.sonaudio.format.IntPcmFrame;

/**
 * Decodes the audio frames of a FLAC stream to PCM samples (RFC 9639 section 9), a frame at a time as each is asked
 * for, so callers that only need the start of the audio can stop early. {@link FlacParser#parse} opens one, in its
 * {@link ParsedFlac}.
 *
 * <p>{@link #nextFrame} throws an {@link EOFException} when the stream ends before a frame does.
 */
public final class FlacDecoder implements IntPcmAudio {

    private static final int SYNC_CODE = 0b11111111111110;

    private static final int LEFT_SIDE = 8;
    private static final int SIDE_RIGHT = 9;
    private static final int MID_SIDE = 10;

    private final StreamInfo streamInfo;
    private final FrameReader reader;
    private long samplesDecoded;

    /**
     * @param inputStream left at the first audio frame
     */
    FlacDecoder(final StreamInfo streamInfo, final InputStream inputStream) {
        this.streamInfo = streamInfo;
        this.reader = new FrameReader(inputStream);
    }

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
        return reader.hasMoreBytes();
    }

    private IntPcmFrame readFrame() throws IOException, InvalidFormatException {
        reader.startFrame();
        final var header = readFrameHeader();
        final var channels = new long[header.channels()][];
        for (var channel = 0; channel < channels.length; channel++) {
            channels[channel] = readSubframe(header.blockSize(), header.subframeBitsPerSample(channel));
        }
        reader.alignToByte();
        reader.readBits(16);
        if (reader.crc16() != 0) {
            throw new InvalidFormatException("FLAC frame fails its CRC-16 check!");
        }
        samplesDecoded += header.blockSize();
        return new IntPcmFrame(decorrelate(header.channelAssignment(), channels), streamInfo.sampleRate());
    }

    private FrameHeader readFrameHeader() throws IOException, InvalidFormatException {
        if (reader.readBits(14) != SYNC_CODE || reader.readBits(1) != 0) {
            throw new InvalidFormatException("FLAC frame doesn't start with a frame header!");
        }
        // The blocking strategy only says how the coded number below counts.
        reader.readBits(1);
        final var blockSizeCode = (int) reader.readBits(4);
        final var sampleRateCode = (int) reader.readBits(4);
        final var channelAssignment = (int) reader.readBits(4);
        if (channelAssignment > MID_SIDE) {
            throw new InvalidFormatException("FLAC frame header has a reserved channel assignment!");
        }
        final var bitDepthCode = (int) reader.readBits(3);
        if (reader.readBits(1) != 0) {
            throw new InvalidFormatException("FLAC frame header has its reserved bit set!");
        }
        skipCodedNumber();
        final var blockSize = readBlockSize(blockSizeCode);
        final var sampleRate = readSampleRate(sampleRateCode);
        reader.readBits(8);
        if (reader.crc8() != 0) {
            throw new InvalidFormatException("FLAC frame header fails its CRC-8 check!");
        }
        final var header = new FrameHeader(blockSize, channelAssignment, readBitDepth(bitDepthCode));
        // Sona takes all three from STREAMINFO to size what it decodes, so a frame that disagrees means one is corrupt.
        if (sampleRate != streamInfo.sampleRate()
                || header.channels() != streamInfo.numberOfChannels()
                || header.bitsPerSample() != streamInfo.bitsPerSample()) {
            throw new InvalidFormatException("FLAC frame header doesn't match STREAMINFO!");
        }
        return header;
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
            case 12 -> (int) reader.readBits(8) * 1000;
            case 13 -> (int) reader.readBits(16);
            case 14 -> (int) reader.readBits(16) * 10;
            default -> throw new InvalidFormatException("FLAC frame header has an invalid sample rate!");
        };
    }

    // The frame or sample number, coded like UTF-8 in up to seven bytes (RFC 9639 section 9.1.5).
    private void skipCodedNumber() throws IOException, InvalidFormatException {
        final var first = (int) reader.readBits(8);
        final var length = Integer.numberOfLeadingZeros(~first << 24);
        if (length == 1 || length > 7) {
            throw new InvalidFormatException("FLAC frame header has an invalid coded number!");
        }
        for (var i = 1; i < length; i++) {
            if (reader.readBits(8) >> 6 != 0b10) {
                throw new InvalidFormatException("FLAC frame header has an invalid coded number!");
            }
        }
    }

    private int readBlockSize(final int code) throws IOException, InvalidFormatException {
        return switch (code) {
            case 0 -> throw new InvalidFormatException("FLAC frame header has a reserved block size!");
            case 1 -> 192;
            case 2, 3, 4, 5 -> 576 << (code - 2);
            case 6 -> (int) reader.readBits(8) + 1;
            case 7 -> (int) reader.readBits(16) + 1;
            default -> 256 << (code - 8);
        };
    }

    private int readBitDepth(final int code) throws InvalidFormatException {
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
        if (reader.readBits(1) != 0) {
            throw new InvalidFormatException("FLAC subframe header doesn't start with a 0 bit!");
        }
        final var type = (int) reader.readBits(6);
        final var wastedBits = reader.readBits(1) == 1 ? reader.readUnary() + 1 : 0;
        final var bitsPerSample = frameBitsPerSample - wastedBits;
        if (bitsPerSample < 1) {
            throw new InvalidFormatException("FLAC subframe has more wasted bits than bits per sample!");
        }
        final long[] samples;
        if (type == 0) {
            samples = new long[blockSize];
            Arrays.fill(samples, reader.readSigned(bitsPerSample));
        } else if (type == 1) {
            samples = readWarmUp(blockSize, blockSize, bitsPerSample);
        } else if (type >= 0b001000 && type <= 0b001100) {
            samples = readFixed(blockSize, type - 0b001000, bitsPerSample);
        } else if (type >= 0b100000) {
            samples = readLinearPredictor(blockSize, type - 0b011111, bitsPerSample);
        } else {
            throw new InvalidFormatException("FLAC subframe has a reserved type: " + type);
        }
        for (var i = 0; i < samples.length; i++) {
            samples[i] <<= wastedBits;
        }
        return samples;
    }

    // RFC 9639 section 9.2.5.
    private long[] readFixed(final int blockSize, final int order, final int bitsPerSample)
            throws IOException, InvalidFormatException {
        final var warmUp = readWarmUp(blockSize, order, bitsPerSample);
        final var samples = readResidual(blockSize, order);
        System.arraycopy(warmUp, 0, samples, 0, order);
        for (var i = order; i < samples.length; i++) {
            samples[i] += switch (order) {
                case 0 -> 0;
                case 1 -> samples[i - 1];
                case 2 -> 2 * samples[i - 1] - samples[i - 2];
                case 3 -> 3 * samples[i - 1] - 3 * samples[i - 2] + samples[i - 3];
                default -> 4 * samples[i - 1] - 6 * samples[i - 2] + 4 * samples[i - 3] - samples[i - 4];
            };
        }
        return samples;
    }

    // RFC 9639 section 9.2.6.
    private long[] readLinearPredictor(final int blockSize, final int order, final int bitsPerSample)
            throws IOException, InvalidFormatException {
        final var warmUp = readWarmUp(blockSize, order, bitsPerSample);
        final var precision = (int) reader.readBits(4) + 1;
        if (precision == 16) {
            throw new InvalidFormatException("FLAC subframe has an invalid coefficient precision!");
        }
        final var shift = (int) reader.readSigned(5);
        if (shift < 0) {
            throw new InvalidFormatException("FLAC subframe has a negative prediction shift!");
        }
        final var coefficients = new long[order];
        for (var j = 0; j < order; j++) {
            coefficients[j] = reader.readSigned(precision);
        }
        final var samples = readResidual(blockSize, order);
        System.arraycopy(warmUp, 0, samples, 0, order);
        for (var i = order; i < samples.length; i++) {
            var prediction = 0L;
            for (var j = 0; j < order; j++) {
                prediction += coefficients[j] * samples[i - 1 - j];
            }
            samples[i] += prediction >> shift;
        }
        return samples;
    }

    // Reads the samples a predictor starts from, stored as they are; a verbatim subframe stores every sample so.
    private long[] readWarmUp(final int blockSize, final int order, final int bitsPerSample)
            throws IOException, InvalidFormatException {
        if (order > blockSize) {
            throw new InvalidFormatException("FLAC subframe's predictor order is larger than its block!");
        }
        final var samples = new long[order];
        for (var i = 0; i < order; i++) {
            samples[i] = reader.readSigned(bitsPerSample);
        }
        return samples;
    }

    // Reads the coded residual (RFC 9639 section 9.2.7), which follows the warm-up samples, so the first `order`
    // values are left 0.
    private long[] readResidual(final int blockSize, final int order) throws IOException, InvalidFormatException {
        final var parameterBits = switch ((int) reader.readBits(2)) {
            case 0 -> 4;
            case 1 -> 5;
            default -> throw new InvalidFormatException("FLAC subframe has a reserved residual coding method!");
        };
        final var escape = (1 << parameterBits) - 1;
        final var partitionOrder = (int) reader.readBits(4);
        final var partitionSize = blockSize >> partitionOrder;
        if (partitionSize << partitionOrder != blockSize || partitionSize < order) {
            throw new InvalidFormatException("FLAC subframe's residual partitions don't fit its block!");
        }
        final var residual = new long[blockSize];
        var i = order;
        for (var partition = 0; partition < 1 << partitionOrder; partition++) {
            final var end = (partition + 1) * partitionSize;
            final var parameter = (int) reader.readBits(parameterBits);
            // Either may be 0 bits, which the bit stream can't read, and stands for all-zero values.
            if (parameter == escape) {
                final var bits = (int) reader.readBits(5);
                for (; i < end; i++) {
                    residual[i] = bits == 0 ? 0 : reader.readSigned(bits);
                }
            } else {
                for (; i < end; i++) {
                    final var quotient = (long) reader.readUnary() << parameter;
                    final var folded = parameter == 0 ? quotient : quotient | reader.readBits(parameter);
                    residual[i] = (folded >>> 1) ^ -(folded & 1);
                }
            }
        }
        return residual;
    }

    // Undoes the stereo decorrelation of RFC 9639 section 4.2.
    private static int[][] decorrelate(final int channelAssignment, final long[][] channels) {
        final var samples = new int[channels.length][channels[0].length];
        for (var i = 0; i < samples[0].length; i++) {
            switch (channelAssignment) {
                case LEFT_SIDE -> {
                    samples[0][i] = (int) channels[0][i];
                    samples[1][i] = (int) (channels[0][i] - channels[1][i]);
                }
                case SIDE_RIGHT -> {
                    samples[0][i] = (int) (channels[0][i] + channels[1][i]);
                    samples[1][i] = (int) channels[1][i];
                }
                case MID_SIDE -> {
                    final var side = channels[1][i];
                    final var mid = (channels[0][i] << 1) | (side & 1);
                    samples[0][i] = (int) ((mid + side) >> 1);
                    samples[1][i] = (int) ((mid - side) >> 1);
                }
                default -> {
                    for (var channel = 0; channel < samples.length; channel++) {
                        samples[channel][i] = (int) channels[channel][i];
                    }
                }
            }
        }
        return samples;
    }

    private record FrameHeader(int blockSize, int channelAssignment, int bitsPerSample) {

        int channels() {
            return channelAssignment < LEFT_SIDE ? channelAssignment + 1 : 2;
        }

        // The side channel has one bit more than the others, as it's a difference of two of them.
        int subframeBitsPerSample(final int channel) {
            final var isSide = switch (channelAssignment) {
                case LEFT_SIDE, MID_SIDE -> channel == 1;
                case SIDE_RIGHT -> channel == 0;
                default -> false;
            };
            return isSide ? bitsPerSample + 1 : bitsPerSample;
        }
    }
}
