package dev.javax.sonaudio.format.opus;

import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.FloatPcmAudio;
import dev.javax.sonaudio.format.FloatPcmFrame;
import dev.javax.sonaudio.format.FrameContinuity;
import dev.javax.sonaudio.format.opus.ChannelSource.Coded;
import dev.javax.sonaudio.format.opus.ChannelSource.Copied;
import dev.javax.sonaudio.format.opus.ChannelSource.Silent;
import dev.javax.sonaudio.format.opus.SampleRange.Dropped;
import dev.javax.sonaudio.format.opus.SampleRange.Kept;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * The audio of an Opus file, which {@link OpusParser#parse} opens in its {@link ParsedOpus}, decoded to floating point
 * a frame at a time as each is asked for. Opus always decodes at 48 kHz, whatever rate the file was recorded at. It
 * decodes as FFmpeg's native Opus decoder does, as AcoustID's fpcalc decodes with it, and drops the samples that its
 * Ogg demuxer has it drop: the pre-skip from the start and the excess the last page's granule position shows from the
 * end.
 *
 * <p>A frame is the output of one Ogg packet, or the samples a SILK resampler still held back after the last. Its
 * channels are the file's, in the order FFmpeg gives them, with the header's output gain applied, and a packet may give
 * none at all, which is not a frame.
 *
 * <p>The decoding of a packet is {@link OpusStream}'s, for each coded stream, which the file's channel mapping then
 * assembles into the file's channels.
 *
 * <p>Its samples match FFmpeg's within 1 at 16 bits on the FATE vectors and on the fixtures, and in the number of
 * samples exactly. They are not bit for bit, because FFmpeg's own result depends on the machine: its x86 SIMD
 * post-filter and de-emphasis, and probably its FFTs, round differently from its C code by 1 least significant bit in
 * a few hundred samples of several million (the reference image's FFmpeg and its {@code -cpuflags 0} output differ so:
 * 345 of 2830080 in testvector01, 530 of 2881920 in testvector11, 1 to 11 in each fixture), and this
 * decoder does the C arithmetic, in the same order and precision.
 *
 * <p>What it reproduces of FFmpeg, where the RFC and libopus do otherwise:
 *
 * <ul>
 *   <li>The output gain is applied once for each output channel, in order, so a channel that is a copy of an earlier
 *       one (a mapping that repeats an index) is scaled twice. The gain is 10 to the power of the header's Q7.8 value
 *       over 20 times 256, as a float, and is applied only when it isn't 0.
 *   <li>A packet that fails to parse, or whose coded streams differ in duration, is dropped whole, and every later
 *       frame has {@link FrameContinuity#AFTER_DECODING_ERROR}. A frame inside a good packet
 *       that can't be decoded is silence of its length, and is no error.
 *   <li>The streams of a multistream packet are synchronised by the fewest samples any gave, and the rest wait for the
 *       next packet. FFmpeg's own accounting loses samples when the streams' delays differ (it indexes the buffered
 *       tail from the wrong base), which this doesn't reproduce, and no fixture has such delays: the four streams of
 *       the FATE {@code tron.6ch.tinypkts} are all CELT.
 *   <li>The SILK resampler's held-back samples come out as a frame of their own after the last packet, as FFmpeg's
 *       drain gives them.
 * </ul>
 */
public final class OpusDecoder implements FloatPcmAudio {

    private static final int SAMPLE_RATE = 48000;
    private static final int BITS_PER_SAMPLE = 16;
    private static final double GAIN_UNITS_PER_DECIBEL = 256.0;
    // How many samples of a stream may wait for the others of a multistream file, whose streams may come out at
    // different lengths.
    private static final int WAITING_CAPACITY = 1024;

    private final OpusParser parser;
    private final OpusStreamInfo streamInfo;
    private final OggStream oggStream;
    private final OpusStream[] streams;
    private final List<ChannelSource> channelSources;
    private final float gain;
    // For each stream of a multistream file, its samples that the others hadn't decoded yet, and how many, and room to
    // lay them before the stream's next samples.
    private final float[][][] waiting;
    private final int[] waitingSamples;
    private final float[][][] joined;
    // Whether there's a next frame is only known once it's decoded, as a packet may give no samples, so hasNextFrame
    // decodes it, and nextFrame gives it.
    private NextFrame nextFrame = new Undecoded();
    // Set once FFmpeg would have returned an error for a packet it dropped, which stops fpcalc reading.
    private FrameContinuity continuity = FrameContinuity.CONTINUOUS;
    // Decoded samples still to drop from the start, as FFmpeg's decoder drops them from what it decodes.
    private int samplesToSkip;

    /**
     * @param parser which reads the framing of each packet
     * @param streamInfo what the file's {@code OpusHead} says
     * @param oggStream the file's Opus stream, at its first audio packet
     */
    OpusDecoder(final OpusParser parser, final OpusStreamInfo streamInfo, final OggStream oggStream) {
        this.parser = parser;
        this.streamInfo = streamInfo;
        this.oggStream = oggStream;
        this.streams = codedStreams(streamInfo);
        this.channelSources = streamInfo.channelSources();
        this.gain = (float) Math.pow(10.0, streamInfo.outputGain() / (20.0 * GAIN_UNITS_PER_DECIBEL));
        this.waiting = waitingBuffers(WAITING_CAPACITY);
        this.waitingSamples = new int[streams.length];
        this.joined = waitingBuffers(WAITING_CAPACITY + OpusStream.OUTPUT_CAPACITY);
    }

    private OpusStream[] codedStreams(final OpusStreamInfo info) {
        final var coded = new OpusStream[info.streamCount()];
        for (var stream = 0; stream < coded.length; stream++) {
            var outputChannels = 1;
            if (stream < info.coupledStreamCount()) {
                outputChannels = 2;
            }
            coded[stream] = new OpusStream(outputChannels);
        }
        return coded;
    }

    // Only a multistream file has streams that come out at different lengths, so only it has anything to wait with.
    private float[][][] waitingBuffers(final int capacity) {
        final var buffers = new float[streams.length][][];
        for (var stream = 0; stream < streams.length; stream++) {
            var channels = 0;
            if (streams.length > 1) {
                channels = streams[stream].output().length;
            }
            buffers[stream] = new float[channels][capacity];
        }
        return buffers;
    }

    /**
     * @return what the file's {@code OpusHead} says, and the length its last page gives
     */
    public OpusStreamInfo streamInfo() {
        return streamInfo;
    }

    @Override
    public int sampleRate() {
        return SAMPLE_RATE;
    }

    @Override
    public int bitsPerSample() {
        return BITS_PER_SAMPLE;
    }

    @Override
    public boolean hasNextFrame() throws IOException, InvalidFormatException {
        return switch (peekNextFrame()) {
            case Decoded _ -> true;
            case Undecoded _, Ended _ -> false;
        };
    }

    @Override
    public FloatPcmFrame nextFrame() throws IOException, InvalidFormatException {
        return switch (peekNextFrame()) {
            case Decoded(final var frame) -> {
                nextFrame = new Undecoded();
                yield frame;
            }
            case Undecoded _, Ended _ -> throw new NoSuchElementException("Opus stream has no more frames!");
        };
    }

    private NextFrame peekNextFrame() throws IOException, InvalidFormatException {
        if (nextFrame instanceof Undecoded) {
            nextFrame = decodeNextFrame();
        }
        return nextFrame;
    }

    // Packets that give no samples are passed over, and once there are none left, the samples the resamplers still hold
    // back are drained, which FFmpeg gives as a frame of its own after the last packet's.
    private NextFrame decodeNextFrame() throws IOException, InvalidFormatException {
        while (oggStream.hasNextPacket()) {
            final var decoded = decodePacket(oggStream.nextAudioPacket());
            if (decoded instanceof Decoded) {
                return decoded;
            }
        }
        final var drained = drainStreams();
        if (drained instanceof Decoded) {
            return drained;
        }
        return new Ended();
    }

    // A packet that FFmpeg's decoder rejects is dropped whole, and every frame after it says so.
    private NextFrame decodePacket(final OggPacket packet) {
        final var bytes = packet.bytes();
        final List<OpusPacket> framings;
        try {
            framings = frameStreams(bytes);
        } catch (final InvalidFormatException undecodable) {
            continuity = FrameContinuity.AFTER_DECODING_ERROR;
            return new Undecoded();
        }
        final var decodedSamples = new int[streams.length];
        var offset = 0;
        for (var stream = 0; stream < streams.length; stream++) {
            final var framing = framings.get(stream);
            decodedSamples[stream] = streams[stream].decodePacket(bytes, offset, framing);
            offset += framing.packetSize();
        }
        return assembleFrame(decodedSamples, packet.startTrim(), packet.endTrim());
    }

    // The streams' parts of a packet follow each other, each saying where it ends but the last (RFC 6716 appendix B),
    // and must all hold the same duration, as FFmpeg checks.
    private List<OpusPacket> frameStreams(final byte[] bytes) throws InvalidFormatException {
        final var framings = new ArrayList<OpusPacket>();
        var offset = 0;
        for (var stream = 0; stream < streams.length; stream++) {
            final var framing = parser.parsePacket(bytes, offset, bytes.length - offset, delimiting(stream));
            if (stream > 0 && framing.sampleCount() != framings.getFirst().sampleCount()) {
                throw new InvalidFormatException("Opus streams of a packet have different durations");
            }
            framings.add(framing);
            offset += framing.packetSize();
        }
        return framings;
    }

    private Delimiting delimiting(final int stream) {
        if (stream == streams.length - 1) {
            return Delimiting.UNDELIMITED;
        }
        return Delimiting.SELF_DELIMITED;
    }

    private NextFrame drainStreams() {
        final var drainedSamples = new int[streams.length];
        for (var stream = 0; stream < streams.length; stream++) {
            drainedSamples[stream] = streams[stream].drain();
        }
        return assembleFrame(drainedSamples, 0, 0);
    }

    // A frame has as many samples as the stream that has decoded the fewest, and the others keep the rest for the next.
    private NextFrame assembleFrame(final int[] decodedSamples, final int startTrim, final int endTrim) {
        final var sources = new float[streams.length][][];
        var shortest = Integer.MAX_VALUE;
        for (var stream = 0; stream < streams.length; stream++) {
            sources[stream] = availableSamples(stream, decodedSamples[stream]);
            shortest = Math.min(shortest, waitingSamples[stream] + decodedSamples[stream]);
        }
        final var range = trimmedRange(shortest, startTrim, endTrim);
        final var frame = framedSamples(sources, range);
        for (var stream = 0; stream < streams.length; stream++) {
            keepWaiting(stream, sources[stream], waitingSamples[stream] + decodedSamples[stream], shortest);
        }
        return frame;
    }

    // The stream's samples that were waiting, then its new ones: in the stream's own buffer when none were waiting.
    private float[][] availableSamples(final int stream, final int decodedSamples) {
        final var decoded = streams[stream].output();
        final var waitingCount = waitingSamples[stream];
        if (waitingCount == 0) {
            return decoded;
        }
        final var target = joined[stream];
        for (var channel = 0; channel < target.length; channel++) {
            System.arraycopy(waiting[stream][channel], 0, target[channel], 0, waitingCount);
            System.arraycopy(decoded[channel], 0, target[channel], waitingCount, decodedSamples);
        }
        return target;
    }

    private void keepWaiting(final int stream, final float[][] source, final int available, final int used) {
        final var kept = Math.min(available - used, WAITING_CAPACITY);
        for (var channel = 0; channel < waiting[stream].length; channel++) {
            System.arraycopy(source[channel], used, waiting[stream][channel], 0, kept);
        }
        waitingSamples[stream] = kept;
    }

    // FFmpeg's decoder drops the pre-skip from the first samples it gives, whole frames of them if need be, and the
    // padding from the end of the packet that has it, but only when there's that much, and the whole frame if that is
    // all
    // there is.
    private SampleRange trimmedRange(final int samples, final int startTrim, final int endTrim) {
        if (startTrim > 0) {
            samplesToSkip = startTrim;
        }
        var from = 0;
        if (samplesToSkip > 0) {
            if (samples <= samplesToSkip) {
                samplesToSkip -= samples;
                return new Dropped();
            }
            from = samplesToSkip;
            samplesToSkip = 0;
        }
        var count = samples - from;
        if (endTrim > 0 && endTrim <= count) {
            count -= endTrim;
        }
        if (count == 0) {
            return new Dropped();
        }
        return new Kept(from, count);
    }

    private NextFrame framedSamples(final float[][][] sources, final SampleRange range) {
        return switch (range) {
            case Dropped _ -> new Undecoded();
            case Kept(final var from, final var count) -> new Decoded(frame(sources, from, count));
        };
    }

    private FloatPcmFrame frame(final float[][][] sources, final int from, final int count) {
        final var samples = new float[channelSources.size()][count];
        for (var channel = 0; channel < samples.length; channel++) {
            switch (channelSources.get(channel)) {
                case Silent _ -> {}
                case Copied(final var earlier) -> System.arraycopy(samples[earlier], 0, samples[channel], 0, count);
                case Coded(final var stream, final var streamChannel) ->
                    System.arraycopy(sources[stream][streamChannel], from, samples[channel], 0, count);
            }
            applyGain(samples[channel]);
        }
        return new FloatPcmFrame(samples, SAMPLE_RATE, continuity);
    }

    // FFmpeg applies the gain to every output channel in turn, so one that copies another is scaled again.
    private void applyGain(final float[] channelSamples) {
        if (streamInfo.outputGain() == 0) {
            return;
        }
        for (var index = 0; index < channelSamples.length; index++) {
            channelSamples[index] *= gain;
        }
    }

    private sealed interface NextFrame permits Decoded, Undecoded, Ended {}

    private record Decoded(FloatPcmFrame frame) implements NextFrame {}

    private record Undecoded() implements NextFrame {}

    private record Ended() implements NextFrame {}
}
