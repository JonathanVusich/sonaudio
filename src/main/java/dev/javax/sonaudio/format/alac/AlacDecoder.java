package dev.javax.sonaudio.format.alac;

import dev.javax.bitstream.BitInputStream;
import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.IntPcmAudio;
import dev.javax.sonaudio.format.IntPcmFrame;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.util.Arrays;
import java.util.NoSuchElementException;

/**
 * The audio of an ALAC file, which {@link AlacParser#parse} opens in its {@link ParsedAlac}, decoded to integers a
 * frame at a time as each is asked for, as FFmpeg's decoder does it, and trimmed as its MOV demuxer does: a frame is
 * a packet that the file's edit list keeps, cut by the start and end trimming the file gives. Each packet is read from
 * the channel where the file's sample tables say it is.
 *
 * <p>A packet is a run of elements, each a single channel (SCE or LFE) or a channel pair (CPE), then an end tag. An
 * element is either stored as it is, or coded: each channel's samples are the sums of an adaptive predictor's
 * estimates, whose coefficients adapt to the errors, and the errors, which are Golomb-Rice coded with the parameter
 * adapting to their size, and runs of zeros coded by their length. A pair's channels are then mixed from a middle and
 * a side channel, and bits that were coded apart are put back as each sample's lowest. The samples have the stream's
 * bit depth, which is 16, 20, 24 or 32 bits: the 20 and 24-bit samples FFmpeg shifts up to fill 32 bits are returned
 * as they are.
 *
 * <p>A packet that FFmpeg's decoder rejects throws an {@link InvalidFormatException}. One whose elements leave a
 * channel out, which FFmpeg decodes to nothing and doesn't call an error, gives no frame.
 */
public final class AlacDecoder implements IntPcmAudio {

    // An element starts with its type, in 3 bits.
    private static final int SINGLE_CHANNEL_ELEMENT = 0;
    private static final int CHANNEL_PAIR_ELEMENT = 1;
    private static final int LOW_FREQUENCY_ELEMENT = 3;
    private static final int END_ELEMENT = 7;
    private static final int ELEMENT_TYPE_BITS = 3;
    private static final int MAX_BITS_PER_SAMPLE = 32;
    private static final int PAIR = 2;
    private static final int MAX_COEFFICIENTS = 32;
    private static final int SIMPLE_PREDICTION_ORDER = 31;
    private static final int SIMPLE_PREDICTION_TYPE = 15;
    // After 9 ones in a row, the value is coded as it is.
    private static final int ESCAPE_UNARY = 9;
    private static final int ESCAPE_COUNT_BITS = 16;
    private static final int MAX_RICE_HISTORY = 0xFFFF;
    private static final int ZERO_RUN_HISTORY = 128;
    private static final int RICE_UPDATE_SHIFT = 9;

    // Where each channel of each layout is among the channels FFmpeg outputs, for the channel that each element
    // starts at: 5.1, for one, has its centre channel's element first, but the centre channel is the third output.
    private static final int[][] OUTPUT_CHANNELS = {
        {0},
        {0, 1},
        {2, 0, 1},
        {2, 0, 1, 3},
        {2, 0, 1, 3, 4},
        {2, 0, 1, 4, 5, 3},
        {2, 0, 1, 4, 5, 6, 3},
        {2, 6, 7, 0, 1, 4, 5, 3}
    };

    private final AlacStreamInfo streamInfo;
    private final AlacCookie cookie;
    private final AlacPackets packets;
    private final SeekableByteChannel channel;
    // The working buffers, which grow to the largest packet and are rewritten for each.
    private byte[] packetBytes = new byte[0];
    private final int[][] samples;
    private final int[][] predictionErrors = new int[PAIR][0];
    private final int[][] lowBits = new int[PAIR][0];
    private final short[][] coefficients = new short[PAIR][MAX_COEFFICIENTS];
    private final int[] predictionTypes = new int[PAIR];
    private final int[] predictionShifts = new int[PAIR];
    private final int[] riceMultipliers = new int[PAIR];
    private final int[] predictionOrders = new int[PAIR];
    private int nextPacket;
    private long pendingSkip;
    private PendingFrame pending = new PendingFrame.Unread();

    AlacDecoder(final AlacStreamInfo streamInfo, final AlacPackets packets, final SeekableByteChannel channel) {
        this.streamInfo = streamInfo;
        this.cookie = streamInfo.cookie();
        this.packets = packets;
        this.channel = channel;
        this.samples = new int[cookie.channelCount()][0];
        this.pendingSkip = packets.initialSkip();
    }

    /**
     * @return what the file says of its audio, which is coded as the stream's cookie says
     */
    public AlacStreamInfo streamInfo() {
        return streamInfo;
    }

    @Override
    public int sampleRate() {
        return cookie.sampleRate();
    }

    @Override
    public int bitsPerSample() {
        return cookie.bitsPerSample();
    }

    @Override
    public boolean hasNextFrame() throws IOException, InvalidFormatException {
        switch (pending) {
            case PendingFrame.Unread _ -> {
                pending = findNextFrame();
                return hasNextFrame();
            }
            case PendingFrame.Ready _ -> {
                return true;
            }
            case PendingFrame.Finished _ -> {
                return false;
            }
        }
    }

    @Override
    public IntPcmFrame nextFrame() throws IOException, InvalidFormatException {
        switch (pending) {
            case PendingFrame.Unread _ -> {
                pending = findNextFrame();
                return nextFrame();
            }
            case PendingFrame.Ready(final var frame) -> {
                pending = new PendingFrame.Unread();
                return frame;
            }
            case PendingFrame.Finished _ -> throw new NoSuchElementException("ALAC file has no more frames!");
        }
    }

    // Decodes packets until one makes a frame that the trimming leaves something of.
    private PendingFrame findNextFrame() throws IOException, InvalidFormatException {
        while (nextPacket < packets.count()) {
            final var packet = nextPacket++;
            switch (decodePacket(packet)) {
                case PacketDecode.Complete(final var count) -> {
                    final var trim = packets.trim(packet, count, pendingSkip);
                    pendingSkip = trim.pendingSkip();
                    if (trim.count() > 0) {
                        return new PendingFrame.Ready(frame(trim));
                    }
                }
                case PacketDecode.Incomplete _ -> {}
            }
        }
        return new PendingFrame.Finished();
    }

    private IntPcmFrame frame(final PacketTrim trim) {
        final var output = new int[samples.length][];
        for (var channel = 0; channel < output.length; channel++) {
            output[channel] = Arrays.copyOfRange(samples[channel], trim.first(), trim.first() + trim.count());
        }
        return new IntPcmFrame(output, cookie.sampleRate());
    }

    private PacketDecode decodePacket(final int packet) throws IOException, InvalidFormatException {
        final var size = packets.size(packet);
        readPacket(packets.offset(packet), size);
        final var bits = BitInputStream.wrap(new ByteArrayInputStream(packetBytes, 0, size), ByteOrder.BIG_ENDIAN);
        try {
            return readElements(bits);
        } catch (final EOFException ignored) {
            throw new InvalidFormatException("ALAC packet ends before its elements and end tag do!");
        }
    }

    private void readPacket(final long offset, final int size) throws IOException {
        if (offset + size > channel.size()) {
            throw new EOFException("ALAC packet runs past the end of the file!");
        }
        if (packetBytes.length < size) {
            packetBytes = new byte[size];
        }
        channel.position(offset);
        final var buffer = ByteBuffer.wrap(packetBytes, 0, size);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new EOFException("ALAC packet is cut short!");
            }
        }
    }

    // The elements of a packet, which fill the channels in order until the end tag.
    private PacketDecode readElements(final BitInputStream bits) throws IOException, InvalidFormatException {
        final var channelCount = cookie.channelCount();
        var channelsDone = 0;
        var elementSamples = 0;
        var element = (int) bits.readBits(ELEMENT_TYPE_BITS);
        while (element != END_ELEMENT) {
            if (element > CHANNEL_PAIR_ELEMENT && element != LOW_FREQUENCY_ELEMENT) {
                throw new InvalidFormatException("ALAC packet has an element of type " + element + "!");
            }
            final var channels = elementChannels(element);
            final var first = OUTPUT_CHANNELS[channelCount - 1][Math.min(channelsDone, channelCount - 1)];
            if (channelsDone + channels > channelCount || first + channels > channelCount) {
                throw new InvalidFormatException("ALAC packet has more channels than its stream does!");
            }
            elementSamples = readElement(bits, first, channels, elementSamples);
            channelsDone += channels;
            element = (int) bits.readBits(ELEMENT_TYPE_BITS);
        }
        if (channelsDone == channelCount && elementSamples > 0) {
            return new PacketDecode.Complete(elementSamples);
        }
        return new PacketDecode.Incomplete();
    }

    private int elementChannels(final int element) {
        return switch (element) {
            case CHANNEL_PAIR_ELEMENT -> PAIR;
            case SINGLE_CHANNEL_ELEMENT, LOW_FREQUENCY_ELEMENT -> 1;
            default -> throw new IllegalArgumentException("Not a channel element: " + element);
        };
    }

    // Reads one element into the output channels from the first, and gives how many samples it has per channel, which
    // every element of a packet has the same of.
    private int readElement(final BitInputStream bits, final int first, final int channels, final int earlierSamples)
            throws IOException, InvalidFormatException {
        // The instance tag, and bits that are unused.
        bits.readBits(16);
        final var hasSize = bits.readBits(1) == 1;
        final var extraBits = (int) bits.readBits(2) << 3;
        final var bps = cookie.bitsPerSample() - extraBits + channels - 1;
        if (bps > MAX_BITS_PER_SAMPLE) {
            throw new InvalidFormatException("ALAC element has samples of " + bps + " bits, which isn't supported!");
        }
        if (bps < 1) {
            throw new InvalidFormatException("ALAC element has samples of " + bps + " bits!");
        }
        final var compressed = bits.readBits(1) == 0;
        var declaredSamples = (long) cookie.frameLength();
        if (hasSize) {
            declaredSamples = bits.readBits(Integer.SIZE);
        }
        final var count = checkedSampleCount(declaredSamples, earlierSamples);
        ensureCapacity(count, earlierSamples);
        final ElementMix mix;
        if (compressed) {
            mix = readCompressed(bits, first, channels, count, bps, extraBits);
        } else {
            mix = readUncompressed(bits, first, channels, count);
        }
        finishElement(first, channels, count, mix);
        return count;
    }

    private int checkedSampleCount(final long count, final int earlierSamples) throws InvalidFormatException {
        if (count == 0 || count > cookie.frameLength()) {
            throw new InvalidFormatException("ALAC element has " + count + " samples!");
        }
        if (earlierSamples != 0 && count != earlierSamples) {
            throw new InvalidFormatException("ALAC elements have " + earlierSamples + " and " + count + " samples!");
        }
        return (int) count;
    }

    private void ensureCapacity(final int count, final int earlierSamples) {
        if (earlierSamples != 0 || samples[0].length >= count) {
            return;
        }
        for (var channel = 0; channel < samples.length; channel++) {
            samples[channel] = new int[count];
        }
        for (var buffer = 0; buffer < PAIR; buffer++) {
            predictionErrors[buffer] = new int[count];
            lowBits[buffer] = new int[count];
        }
    }

    private ElementMix readUncompressed(final BitInputStream bits, final int first, final int channels, final int count)
            throws IOException {
        final var depth = cookie.bitsPerSample();
        for (var sample = 0; sample < count; sample++) {
            for (var channel = 0; channel < channels; channel++) {
                samples[first + channel][sample] = AlacUtils.signExtend((int) bits.readBits(depth), depth);
            }
        }
        return new ElementMix(0, 0, 0);
    }

    private ElementMix readCompressed(
            final BitInputStream bits,
            final int first,
            final int channels,
            final int count,
            final int bps,
            final int extraBits)
            throws IOException, InvalidFormatException {
        if (cookie.riceLimit() == 0) {
            throw new InvalidFormatException(
                    "ALAC element is compressed with a Rice limit of 0, which isn't supported!");
        }
        final var shift = (int) bits.readBits(Byte.SIZE);
        final var weight = (int) bits.readBits(Byte.SIZE);
        if (channels == PAIR && weight != 0 && shift > Integer.SIZE - 1) {
            throw new InvalidFormatException("ALAC element shifts a pair by " + shift + " bits!");
        }
        for (var channel = 0; channel < channels; channel++) {
            readPredictor(bits, channel);
        }
        if (extraBits > 0) {
            readLowBits(bits, channels, count, extraBits);
        }
        for (var channel = 0; channel < channels; channel++) {
            final var multiplier = riceMultipliers[channel] * cookie.riceHistoryMultiplier() / 4;
            readResiduals(bits, predictionErrors[channel], count, bps, multiplier);
            predict(channel, samples[first + channel], count, bps);
        }
        return new ElementMix(shift, weight, extraBits);
    }

    private void readPredictor(final BitInputStream bits, final int channel)
            throws IOException, InvalidFormatException {
        predictionTypes[channel] = (int) bits.readBits(4);
        predictionShifts[channel] = (int) bits.readBits(4);
        riceMultipliers[channel] = (int) bits.readBits(3);
        final var order = (int) bits.readBits(5);
        predictionOrders[channel] = order;
        if (order >= cookie.frameLength() || predictionShifts[channel] == 0) {
            throw new InvalidFormatException("ALAC predictor has an order of " + order + " and a shift of 0!");
        }
        for (var coefficient = order - 1; coefficient >= 0; coefficient--) {
            coefficients[channel][coefficient] = (short) bits.readBits(Short.SIZE);
        }
    }

    // The low bits of each sample, of each channel in turn, which come before the samples' residuals.
    private void readLowBits(final BitInputStream bits, final int channels, final int count, final int extraBits)
            throws IOException {
        for (var sample = 0; sample < count; sample++) {
            for (var channel = 0; channel < channels; channel++) {
                lowBits[channel][sample] = (int) bits.readBits(extraBits);
            }
        }
    }

    // The adaptive Golomb-Rice decoder: each residual is a unary prefix and a suffix of k bits, with the parameter k
    // set by a running history of the residuals' size. After a run of small residuals, the history is low, and the
    // number of zero residuals that follow is coded.
    private void readResiduals(
            final BitInputStream bits, final int[] output, final int count, final int bps, final int multiplier)
            throws IOException {
        var history = cookie.riceInitialHistory();
        var signModifier = 0;
        for (var sample = 0; sample < count; sample++) {
            final var parameter =
                    Math.min(AlacUtils.floorLog2((history >>> RICE_UPDATE_SHIFT) + 3), cookie.riceLimit());
            final var value = (readScalar(bits, parameter, bps) + signModifier) & 0xFFFF_FFFFL;
            signModifier = 0;
            output[sample] = (int) (value >>> 1) ^ -(int) (value & 1);
            history = nextHistory(history, value, multiplier);
            if (Integer.compareUnsigned(history, ZERO_RUN_HISTORY) < 0 && sample + 1 < count) {
                final var run = readZeroRun(bits, output, sample, count, history);
                sample += run;
                if (run <= MAX_RICE_HISTORY) {
                    signModifier = 1;
                }
                history = 0;
            }
        }
    }

    private int nextHistory(final int history, final long value, final int multiplier) {
        if (value > MAX_RICE_HISTORY) {
            return MAX_RICE_HISTORY;
        }
        return history + (int) value * multiplier - ((history * multiplier) >>> RICE_UPDATE_SHIFT);
    }

    // The residuals after the one at the sample are zero for as many as the file says, but not past the end.
    private int readZeroRun(
            final BitInputStream bits, final int[] output, final int sample, final int count, final int history)
            throws IOException {
        final var parameter = Math.min(7 - AlacUtils.floorLog2(history) + ((history + 16) >>> 6), cookie.riceLimit());
        var run = (int) readScalar(bits, parameter, ESCAPE_COUNT_BITS);
        if (run > 0) {
            run = Math.min(run, count - sample - 1);
            Arrays.fill(output, sample + 1, sample + 1 + run, 0);
        }
        return run;
    }

    // One value: a unary prefix of up to 9 ones, which is the value's high part, and k bits for the low part, which
    // the format splits strangely: the value is prefix * (2^k - 1) plus the suffix less one, unless the suffix is 0 or
    // 1, when it only takes k - 1 bits. Nine ones mean that the value follows as it is.
    private long readScalar(final BitInputStream bits, final int parameter, final int bps) throws IOException {
        var prefix = 0;
        while (prefix < ESCAPE_UNARY && bits.readBits(1) != 0) {
            prefix++;
        }
        if (prefix == ESCAPE_UNARY) {
            return bits.readBits(bps);
        }
        if (parameter == 1) {
            return prefix;
        }
        final var value = ((long) prefix << parameter) - prefix;
        final var high = bits.readBits(parameter - 1);
        if (high == 0) {
            return value;
        }
        final var suffix = (high << 1) | bits.readBits(1);
        return value + suffix - 1;
    }

    // Turns a channel's residuals into samples, by the predictor the element says.
    private void predict(final int channel, final int[] output, final int count, final int bps) {
        final var errors = predictionErrors[channel];
        if (predictionTypes[channel] == SIMPLE_PREDICTION_TYPE) {
            predictFirstOrder(errors, errors, count, bps);
        }
        final var order = predictionOrders[channel];
        output[0] = errors[0];
        if (count <= 1) {
            return;
        }
        if (order == 0) {
            System.arraycopy(errors, 1, output, 1, count - 1);
        } else if (order == SIMPLE_PREDICTION_ORDER) {
            predictFirstOrder(errors, output, count, bps);
        } else {
            predictAdaptively(channel, errors, output, count, bps);
        }
    }

    private void predictFirstOrder(final int[] errors, final int[] output, final int count, final int bps) {
        output[0] = errors[0];
        for (var sample = 1; sample < count; sample++) {
            output[sample] = AlacUtils.signExtend(output[sample - 1] + errors[sample], bps);
        }
    }

    // The adaptive filter: after its first samples, which are sums of the errors, each sample is its predecessor
    // (from before the filter's window) plus the filter's estimate of the change, plus the error; and the filter's
    // coefficients move by the sign of the changes, in the direction of the error's, while the error lasts.
    private void predictAdaptively(
            final int channel, final int[] errors, final int[] output, final int count, final int bps) {
        final var order = predictionOrders[channel];
        final var shift = predictionShifts[channel];
        final var weights = coefficients[channel];
        var sample = 1;
        for (; sample <= order && sample < count; sample++) {
            output[sample] = AlacUtils.signExtend(output[sample - 1] + errors[sample], bps);
        }
        for (; sample < count; sample++) {
            final var windowStart = sample - order;
            final var base = output[windowStart - 1];
            var sum = 0;
            for (var tap = 0; tap < order; tap++) {
                sum += (output[windowStart + tap] - base) * weights[tap];
            }
            final var estimate = (int) (((long) sum + (1L << (shift - 1))) >> shift);
            final var error = errors[sample];
            output[sample] = AlacUtils.signExtend(estimate + base + error, bps);
            adapt(weights, output, windowStart, base, error, order, shift);
        }
    }

    private void adapt(
            final short[] weights,
            final int[] output,
            final int windowStart,
            final int base,
            final int error,
            final int order,
            final int shift) {
        final var errorSign = Integer.signum(error);
        var remaining = error;
        for (var tap = 0; errorSign != 0 && tap < order && remaining * errorSign > 0; tap++) {
            var change = base - output[windowStart + tap];
            final var sign = Integer.signum(change) * errorSign;
            weights[tap] -= (short) sign;
            change *= sign;
            remaining -= (change >> shift) * (tap + 1);
        }
    }

    // Mixes a pair's channels, puts the low bits back, and narrows the samples to the stream's bit depth, as the output
    // of FFmpeg's decoder is, which keeps 16-bit samples in 16 bits and the others in the low bits of 32.
    private void finishElement(final int first, final int channels, final int count, final ElementMix mix) {
        if (channels == PAIR && mix.weight() != 0) {
            unmix(samples[first], samples[first + 1], count, mix);
        }
        if (mix.extraBits() > 0) {
            for (var channel = 0; channel < channels; channel++) {
                appendLowBits(samples[first + channel], lowBits[channel], count, mix.extraBits());
            }
        }
        final var depth = cookie.bitsPerSample();
        for (var channel = 0; channel < channels; channel++) {
            narrow(samples[first + channel], count, depth);
        }
    }

    // A pair is coded as a difference (its first channel) and a middle (its second): the second output channel is the
    // difference less a share of the middle, and the first is the middle plus the second.
    private void unmix(final int[] first, final int[] second, final int count, final ElementMix mix) {
        for (var sample = 0; sample < count; sample++) {
            final var difference = first[sample];
            final var middle = second[sample];
            final var right = difference - ((middle * mix.weight()) >> mix.shift());
            first[sample] = middle + right;
            second[sample] = right;
        }
    }

    private void appendLowBits(final int[] output, final int[] low, final int count, final int extraBits) {
        for (var sample = 0; sample < count; sample++) {
            output[sample] = (output[sample] << extraBits) | low[sample];
        }
    }

    private void narrow(final int[] output, final int count, final int depth) {
        if (depth == MAX_BITS_PER_SAMPLE) {
            return;
        }
        for (var sample = 0; sample < count; sample++) {
            output[sample] = AlacUtils.signExtend(output[sample], depth);
        }
    }
}
