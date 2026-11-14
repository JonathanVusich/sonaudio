package dev.javax.sonaudio.format.opus;

import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.opus.FrameRedundancy.None;
import dev.javax.sonaudio.format.opus.FrameRedundancy.Present;
import java.util.Arrays;

/**
 * One coded stream of an Opus file, decoded to 48 kHz as FFmpeg's {@code dec.c} decodes one ({@code
 * OpusStreamContext}). A stream is a mono or a stereo signal, which a multistream file has several of. The stream
 * drives the layers' decoders: it starts the {@link RangeDecoder} on each frame, has {@link SilkDecoder} decode a SILK or
 * hybrid frame's low band and {@link SilkResampler} bring it to 48 kHz, reads the redundancy flags that follow, has
 * {@link CeltDecoder} decode the CELT part and mixes it in, and fades a redundancy frame in or out. It keeps what has to
 * outlive a frame: the delay the resampling puts on SILK, which CELT's output waits for in a hybrid frame, the
 * redundancy fade still to finish, and the state of the decoders.
 *
 * <p>The resampler holds back the first samples of SILK's output until it has the 17 it needs to mirror the start, so
 * the first frame of narrowband SILK comes out 24 samples short (at 48 kHz: 4 input samples of silence at 8 kHz), and
 * none of the other bandwidths' do. The held-back samples are given before the next CELT frame, when SILK changes
 * rate, or in a frame of their own after the last packet ({@link #drain}), as FFmpeg's drain does, with the previous
 * frame's timestamp.
 *
 * <p>Where a frame can't be decoded, FFmpeg gives silence of the frame's length in its place, and so does this.
 */
final class OpusStream {

    /**
     * How many samples per channel a packet's output may have: a packet holds at most 120 ms, which is 5760 samples at
     * 48 kHz, and a flush of the resampler adds a few.
     */
    static final int OUTPUT_CAPACITY = 5760 + 1024;

    private static final int REDUNDANCY_FRAME_SAMPLES = 240;
    private static final int FADE = RedundancyFadeUtils.FADE_SAMPLES;
    // A SILK-only frame has redundancy when it has this many bits to spare, a hybrid frame 37 as well as a flag.
    private static final int SILK_REDUNDANCY_BITS = 17;
    private static final int HYBRID_REDUNDANCY_BITS = 37;
    private static final int HYBRID_REDUNDANCY_FLAG_LOG_PROBABILITY = 12;
    private static final int HYBRID_REDUNDANCY_SIZES = 256;
    private static final int HYBRID_REDUNDANCY_MIN_SIZE = 2;

    private final int outputChannels;
    private final RangeDecoder rangeDecoder = new RangeDecoder();
    private final RangeDecoder redundancyDecoder = new RangeDecoder();
    private final SilkDecoder silk;
    private final CeltDecoder celt;
    private final SilkResampler resampler;
    private final float[][] silkOutput;
    private final float[][] celtOutput;
    private final float[][] redundancyOutput;
    // The samples of CELT's last hybrid frame that its SILK layer, delayed by the resampler, hasn't reached yet.
    private final float[][] celtDelay;
    // What the packet's frames decode to, which the decoder reads once the packet is done.
    private final float[][] output;
    private int outputSamples;
    private int celtDelaySamples;
    // How many samples the resampler holds back of the frames so far, which it gives when it is flushed.
    private int delayedSamples;
    // The start of the fade of a redundancy frame that the last frame didn't have room to finish.
    private int redundancyIndex;

    /**
     * @param outputChannels how many channels the stream is output as, 1 for a mono stream and 2 for a stereo stream
     */
    OpusStream(final int outputChannels) {
        this.outputChannels = outputChannels;
        this.silk = new SilkDecoder(outputChannels);
        this.celt = new CeltDecoder(outputChannels);
        this.resampler = new SilkResampler(outputChannels);
        this.silkOutput = new float[outputChannels][SilkDecoder.MAX_FRAME_SAMPLES];
        this.celtOutput = new float[outputChannels][CeltDecoder.MAX_FRAME_SAMPLES];
        this.redundancyOutput = new float[outputChannels][CeltDecoder.MAX_FRAME_SAMPLES];
        this.celtDelay = new float[outputChannels][CeltDecoder.MAX_FRAME_SAMPLES];
        this.output = new float[outputChannels][OUTPUT_CAPACITY];
    }

    /**
     * @return the stream's samples at 48 kHz, for each output channel, of the last packet or drain: the first
     *     {@link #decodePacket} or {@link #drain} returned of them are the samples. It is a working buffer, which the
     *     next packet overwrites.
     */
    float[][] output() {
        return output;
    }

    /**
     * Decodes the stream's part of a packet to {@link #output}: the samples the resampler still owed from before, when
     * this packet doesn't use SILK or at the rate it was open for, and then every frame of the packet.
     *
     * @param data the bytes of the packet, which may hold other streams' parts too
     * @param offset where this stream's part starts in them
     * @param packet the framing of the part
     * @return how many samples were written to each channel of the output, which is the packet's own less what the
     *     resampler holds back, plus what it gives back
     */
    int decodePacket(final byte[] data, final int offset, final OpusPacket packet) {
        outputSamples = 0;
        if (needsResamplerFlush(packet)) {
            flushResampler(delayedSamples);
        }
        for (var frame = 0; frame < packet.frameCount(); frame++) {
            final var frameStart = offset + packet.frameOffset(frame);
            outputSamples += decodeFrameOrSilence(data, frameStart, packet.frameSize(frame), packet);
        }
        return outputSamples;
    }

    /**
     * Gives the samples that the resampler still holds back at the end of the stream, to {@link #output}.
     *
     * @return how many samples were written to each channel, which is 0 when it holds none
     */
    int drain() {
        outputSamples = 0;
        if (resampler.state() == ResamplerState.OPEN && delayedSamples > 0) {
            flushResampler(delayedSamples);
        }
        return outputSamples;
    }

    // The resampler is given up when a packet doesn't use SILK, or uses it at another rate, as FFmpeg does.
    private boolean needsResamplerFlush(final OpusPacket packet) {
        if (resampler.state() == ResamplerState.CLOSED) {
            return false;
        }
        final var silkRate = packet.bandwidth().silkSampleRate();
        return packet.mode() == OpusMode.CELT || resampler.inputSampleRate() != silkRate;
    }

    private void flushResampler(final int samples) {
        resampler.flush(output, outputSamples, samples);
        // The delay left in a hybrid frame's CELT output only has this many samples to wait for, so it ends here.
        final var waiting = Math.min(celtDelaySamples, samples);
        for (var channel = 0; channel < outputChannels; channel++) {
            for (var index = 0; index < waiting; index++) {
                output[channel][outputSamples + index] += celtDelay[channel][index];
            }
        }
        celtDelaySamples = 0;
        fadeOutstandingRedundancy();
        resampler.close();
        outputSamples += samples;
        delayedSamples = 0;
    }

    // A frame that doesn't decode is silence the length of the frame, and the packet's others go on.
    private int decodeFrameOrSilence(
            final byte[] data, final int frameStart, final int frameSize, final OpusPacket packet) {
        try {
            return decodeFrame(data, frameStart, frameSize, packet);
        } catch (final InvalidFormatException undecodable) {
            for (var channel = 0; channel < outputChannels; channel++) {
                Arrays.fill(output[channel], outputSamples, outputSamples + packet.frameSamples(), 0f);
            }
            return packet.frameSamples();
        }
    }

    private int decodeFrame(final byte[] data, final int frameStart, final int frameSize, final OpusPacket packet)
            throws InvalidFormatException {
        final var delayedBefore = delayedSamples;
        rangeDecoder.initialise(data, frameStart, frameSize);
        final var samples = decodeSilkLayer(packet);
        final var redundancy = readRedundancy(packet, frameSize);
        final var celtSize = celtFrameSize(frameSize, redundancy);
        if (redundancy instanceof Present(final var size, final var position)
                && position == RedundancyPosition.AT_START) {
            decodeRedundancy(data, frameStart + celtSize, size, packet);
            celt.reset();
        }
        decodeCeltLayer(packet, samples, celtSize);
        fadeOutstandingRedundancy();
        if (redundancy instanceof Present(final var size, final var position)) {
            blendRedundancy(data, frameStart + celtSize, size, position, packet, samples, delayedBefore);
        }
        return samples;
    }

    private int decodeSilkLayer(final OpusPacket packet) throws InvalidFormatException {
        if (packet.mode() == OpusMode.CELT) {
            silk.reset();
            return packet.frameSamples();
        }
        if (resampler.state() == ResamplerState.CLOSED) {
            resampler.open(packet.bandwidth());
        }
        final var silkBandwidth = packet.bandwidth().silkBandwidth();
        final var silkSamples =
                silk.decodeFrame(rangeDecoder, silkBandwidth, packet.coding(), packet.frameMillis(), silkOutput);
        final var samples = resampler.convert(silkOutput, silkSamples, output, outputSamples, packet.frameSamples());
        delayedSamples += packet.frameSamples() - samples;
        return samples;
    }

    // RFC 6716 section 4.5.1.1: a SILK frame with room to spare has redundancy, and a hybrid frame says so.
    private FrameRedundancy readRedundancy(final OpusPacket packet, final int frameSize) {
        final var consumed = rangeDecoder.tell();
        final var spare = frameSize * Byte.SIZE - consumed;
        final var present = switch (packet.mode()) {
            case SILK -> spare >= SILK_REDUNDANCY_BITS;
            case HYBRID ->
                spare >= HYBRID_REDUNDANCY_BITS && rangeDecoder.decodeBit(HYBRID_REDUNDANCY_FLAG_LOG_PROBABILITY);
            case CELT -> false;
        };
        if (!present) {
            return new None();
        }
        var position = RedundancyPosition.AT_END;
        if (rangeDecoder.decodeBit(1)) {
            position = RedundancyPosition.AT_START;
        }
        return new Present(redundancySize(packet, frameSize, consumed), position);
    }

    private int redundancySize(final OpusPacket packet, final int frameSize, final int consumed) {
        if (packet.mode() == OpusMode.HYBRID) {
            return rangeDecoder.decodeUniform(HYBRID_REDUNDANCY_SIZES) + HYBRID_REDUNDANCY_MIN_SIZE;
        }
        return frameSize - (consumed + 7) / Byte.SIZE;
    }

    // What is left of the frame once the redundancy is cut off, which can't be less than nothing.
    private int celtFrameSize(final int frameSize, final FrameRedundancy redundancy) throws InvalidFormatException {
        return switch (redundancy) {
            case None _ -> frameSize;
            case Present(final var size, final var position) -> {
                if (size > frameSize) {
                    throw new InvalidFormatException("Invalid Opus redundancy frame size");
                }
                yield frameSize - size;
            }
        };
    }

    private void decodeRedundancy(final byte[] data, final int redundancyStart, final int size, final OpusPacket packet)
            throws InvalidFormatException {
        redundancyDecoder.initialise(data, redundancyStart, size);
        celt.decodeFrame(
                redundancyDecoder,
                packet.coding(),
                REDUNDANCY_FRAME_SAMPLES,
                0,
                packet.bandwidth().celtEndBand(),
                redundancyOutput);
    }

    private void decodeCeltLayer(final OpusPacket packet, final int samples, final int celtSize)
            throws InvalidFormatException {
        if (packet.mode() == OpusMode.SILK) {
            celt.reset();
            return;
        }
        // CELT alone leaves nothing for a delay to wait for, so one left over from a hybrid frame is dropped.
        var mixStart = outputSamples;
        var mixSamples = samples;
        if (packet.mode() == OpusMode.HYBRID) {
            mixStart += celtDelaySamples;
            mixSamples -= celtDelaySamples;
            addCeltDelay();
        }
        celtDelaySamples = 0;
        rangeDecoder.shrink(celtSize);
        decodeCeltFrame(packet);
        mixCeltOutput(packet, mixStart, mixSamples);
    }

    private void decodeCeltFrame(final OpusPacket packet) throws InvalidFormatException {
        var startBand = 0;
        if (packet.mode() == OpusMode.HYBRID) {
            startBand = CeltDecoder.HYBRID_START_BAND;
        }
        celt.decodeFrame(
                rangeDecoder,
                packet.coding(),
                packet.frameSamples(),
                startBand,
                packet.bandwidth().celtEndBand(),
                celtOutput);
    }

    // The delayed CELT samples of the last hybrid frame meet SILK's first samples of this one, which the resampler
    // gave.
    private void addCeltDelay() {
        for (var channel = 0; channel < outputChannels; channel++) {
            for (var index = 0; index < celtDelaySamples; index++) {
                output[channel][outputSamples + index] += celtDelay[channel][index];
            }
        }
    }

    // A hybrid frame adds its CELT samples to SILK's, and keeps those that SILK's resampler hasn't reached yet.
    private void mixCeltOutput(final OpusPacket packet, final int mixStart, final int mixSamples) {
        for (var channel = 0; channel < outputChannels; channel++) {
            switch (packet.mode()) {
                case HYBRID -> {
                    for (var index = 0; index < mixSamples; index++) {
                        output[channel][mixStart + index] += celtOutput[channel][index];
                    }
                    final var kept = packet.frameSamples() - mixSamples;
                    System.arraycopy(celtOutput[channel], mixSamples, celtDelay[channel], 0, kept);
                    celtDelaySamples = kept;
                }
                case CELT -> System.arraycopy(celtOutput[channel], 0, output[channel], mixStart, packet.frameSamples());
                case SILK -> {}
            }
        }
    }

    // The fade that a redundancy frame at a frame's end had no room for goes on into the start of the next.
    private void fadeOutstandingRedundancy() {
        if (redundancyIndex == 0) {
            return;
        }
        for (var channel = 0; channel < outputChannels; channel++) {
            final var samples = output[channel];
            RedundancyFadeUtils.crossFade(
                    samples,
                    outputSamples,
                    samples,
                    outputSamples,
                    redundancyOutput[channel],
                    FADE + redundancyIndex,
                    redundancyIndex,
                    FADE - redundancyIndex);
        }
        redundancyIndex = 0;
    }

    private void blendRedundancy(
            final byte[] data,
            final int redundancyStart,
            final int size,
            final RedundancyPosition position,
            final OpusPacket packet,
            final int samples,
            final int delayedBefore)
            throws InvalidFormatException {
        switch (position) {
            case AT_START -> fadeRedundancyIn(delayedBefore);
            case AT_END -> {
                celt.reset();
                decodeRedundancy(data, redundancyStart, size, packet);
                fadeRedundancyOut(samples, delayedBefore);
            }
        }
    }

    // The redundancy frame's first 120 samples take the place of the frame's, and its last 120 fade into them.
    private void fadeRedundancyIn(final int delayedBefore) {
        final var start = outputSamples + delayedBefore;
        for (var channel = 0; channel < outputChannels; channel++) {
            final var samples = output[channel];
            System.arraycopy(redundancyOutput[channel], 0, samples, start, FADE);
            RedundancyFadeUtils.crossFade(
                    samples, start + FADE, redundancyOutput[channel], FADE, samples, start + FADE, 0, FADE);
        }
    }

    // The frame's last 120 samples fade into the redundancy frame's, which carry on into the next frame if the
    // resampler's delay left them no room.
    private void fadeRedundancyOut(final int samples, final int delayedBefore) {
        final var start = outputSamples + samples - FADE + delayedBefore;
        for (var channel = 0; channel < outputChannels; channel++) {
            final var target = output[channel];
            RedundancyFadeUtils.crossFade(
                    target, start, target, start, redundancyOutput[channel], FADE, 0, FADE - delayedBefore);
        }
        if (delayedBefore > 0) {
            redundancyIndex = FADE - delayedBefore;
        }
    }
}
