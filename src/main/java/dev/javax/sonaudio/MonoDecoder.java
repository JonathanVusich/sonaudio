package dev.javax.sonaudio;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * An audio stream decoded by FFmpeg and converted by its libswresample to 16-bit mono at the rate it's given, as
 * AcoustID's {@code fpcalc} does. It reads the file only as far as it's iterated, and it can be iterated once. Decoding
 * stops at the first packet FFmpeg's decoder rejects, as {@code fpcalc} does.
 */
final class MonoDecoder implements Iterable<PcmChunk>, AutoCloseable {

    private static final int MONO = 1;
    private static final long FRAME_SIZE = 512;
    private static final long PACKET_SIZE = 128;
    private static final long PARAMETERS_SIZE = 256;

    private enum Stage {
        READING,
        FLUSHING_DECODER,
        FLUSHING_RESAMPLER,
        DONE
    }

    private final FfmpegLibrary ffmpeg;
    private final Arena arena = Arena.ofConfined();
    private final int streamIndex;
    private final int sampleRate;
    private final MemorySegment decoderHolder = arena.allocate(ADDRESS);
    private final MemorySegment resamplerHolder = arena.allocate(ADDRESS);
    private final MemorySegment packetHolder = arena.allocate(ADDRESS);
    private final MemorySegment frameHolder = arena.allocate(ADDRESS);
    private final MemorySegment outputPlanes = arena.allocate(ADDRESS);
    private final MemorySegment context;
    private MemorySegment output = Arena.ofAuto().allocate(1);
    private Stage stage = Stage.READING;

    MonoDecoder(
            final FfmpegLibrary ffmpeg,
            final MemorySegment context,
            final int streamIndex,
            final MemorySegment parameters,
            final int sampleRate)
            throws UnreadableAudioException {
        this.ffmpeg = ffmpeg;
        this.context = context;
        this.streamIndex = streamIndex;
        this.sampleRate = sampleRate;
        final var codecId = parameters.reinterpret(PARAMETERS_SIZE).get(JAVA_INT, FfmpegLibrary.PARAMETERS_CODEC_ID);
        final var decoder = ffmpeg.callPointer("avcodec_find_decoder", codecId);
        if (decoder.equals(MemorySegment.NULL)) {
            throw new UnreadableAudioException("FFmpeg has no decoder for codec %d".formatted(codecId));
        }
        decoderHolder.set(ADDRESS, 0, ffmpeg.callPointer("avcodec_alloc_context3", decoder));
        final var decoderContext = decoderHolder.get(ADDRESS, 0);
        if (ffmpeg.callInt("avcodec_parameters_to_context", decoderContext, parameters) < 0
                || ffmpeg.callInt("avcodec_open2", decoderContext, decoder, MemorySegment.NULL) < 0) {
            throw new UnreadableAudioException("FFmpeg can't open its decoder for codec %d".formatted(codecId));
        }
        packetHolder.set(ADDRESS, 0, ffmpeg.callPointer("av_packet_alloc"));
        frameHolder.set(ADDRESS, 0, ffmpeg.callPointer("av_frame_alloc"));
    }

    @Override
    public Iterator<PcmChunk> iterator() {
        return new Iterator<>() {
            private PcmChunk next = readChunk();

            @Override
            public boolean hasNext() {
                return next != null;
            }

            @Override
            public PcmChunk next() {
                if (next == null) {
                    throw new NoSuchElementException();
                }
                final var chunk = next;
                next = readChunk();
                return chunk;
            }
        };
    }

    private PcmChunk readChunk() {
        while (stage != Stage.DONE) {
            final var chunk =
                    switch (stage) {
                        case READING -> decodeNext();
                        case FLUSHING_DECODER -> flushDecoder();
                        case FLUSHING_RESAMPLER -> flushResampler();
                        case DONE -> null;
                    };
            if (chunk != null) {
                return chunk;
            }
        }
        return null;
    }

    private PcmChunk decodeNext() {
        final var decoderContext = decoderHolder.get(ADDRESS, 0);
        final var frame = frameHolder.get(ADDRESS, 0);
        final var result = ffmpeg.callInt("avcodec_receive_frame", decoderContext, frame);
        if (result == 0) {
            return convertDecoded(frame);
        }
        if (result == FfmpegLibrary.AVERROR_EAGAIN) {
            sendNextPacket(decoderContext);
            return null;
        }
        stage = Stage.FLUSHING_RESAMPLER;
        return null;
    }

    private void sendNextPacket(final MemorySegment decoderContext) {
        final var packet = packetHolder.get(ADDRESS, 0);
        if (ffmpeg.callInt("av_read_frame", context, packet) < 0) {
            ffmpeg.callInt("avcodec_send_packet", decoderContext, MemorySegment.NULL);
            stage = Stage.FLUSHING_DECODER;
            return;
        }
        final var packetStream = packet.reinterpret(PACKET_SIZE).get(JAVA_INT, FfmpegLibrary.PACKET_STREAM_INDEX);
        if (packetStream == streamIndex) {
            final var sent = ffmpeg.callInt("avcodec_send_packet", decoderContext, packet);
            if (sent < 0 && sent != FfmpegLibrary.AVERROR_EAGAIN) {
                stage = Stage.FLUSHING_RESAMPLER;
            }
        }
        ffmpeg.callVoid("av_packet_unref", packet);
    }

    private PcmChunk flushDecoder() {
        final var frame = frameHolder.get(ADDRESS, 0);
        if (ffmpeg.callInt("avcodec_receive_frame", decoderHolder.get(ADDRESS, 0), frame) == 0) {
            return convertDecoded(frame);
        }
        stage = Stage.FLUSHING_RESAMPLER;
        return null;
    }

    private PcmChunk flushResampler() {
        stage = Stage.DONE;
        final var resampler = resamplerHolder.get(ADDRESS, 0);
        if (resampler.equals(MemorySegment.NULL)) {
            return null;
        }
        return convert(resampler, MemorySegment.NULL, 0);
    }

    private PcmChunk convertDecoded(final MemorySegment frame) {
        final var decoded = frame.reinterpret(FRAME_SIZE);
        if (resamplerHolder.get(ADDRESS, 0).equals(MemorySegment.NULL)) {
            openResampler(decoded);
        }
        final var samples = decoded.get(JAVA_INT, FfmpegLibrary.FRAME_NB_SAMPLES);
        final var planes = decoded.get(ADDRESS, FfmpegLibrary.FRAME_EXTENDED_DATA);
        final var converted = convert(resamplerHolder.get(ADDRESS, 0), planes, samples);
        ffmpeg.callVoid("av_frame_unref", frame);
        return converted;
    }

    // The resampler takes the layout, format and rate the decoder gives its first frame, as fpcalc's does.
    private void openResampler(final MemorySegment decoded) {
        final var resampler = ffmpeg.callPointer("swr_alloc");
        final var layout = decoded.asSlice(FfmpegLibrary.FRAME_CH_LAYOUT, FfmpegLibrary.CHANNEL_LAYOUT_SIZE);
        final var mono = arena.allocate(FfmpegLibrary.CHANNEL_LAYOUT_SIZE);
        ffmpeg.callVoid("av_channel_layout_default", mono, MONO);
        ffmpeg.callInt("av_opt_set_chlayout", resampler, arena.allocateFrom("in_chlayout"), layout, 0);
        ffmpeg.callInt("av_opt_set_chlayout", resampler, arena.allocateFrom("out_chlayout"), mono, 0);
        setInt(resampler, "in_sample_rate", decoded.get(JAVA_INT, FfmpegLibrary.FRAME_SAMPLE_RATE));
        setInt(resampler, "out_sample_rate", sampleRate);
        setFormat(resampler, "in_sample_fmt", decoded.get(JAVA_INT, FfmpegLibrary.FRAME_FORMAT));
        setFormat(resampler, "out_sample_fmt", FfmpegLibrary.AV_SAMPLE_FMT_S16);
        ffmpeg.callInt("swr_init", resampler);
        resamplerHolder.set(ADDRESS, 0, resampler);
    }

    private void setInt(final MemorySegment resampler, final String option, final long value) {
        ffmpeg.callInt("av_opt_set_int", resampler, arena.allocateFrom(option), value, 0);
    }

    private void setFormat(final MemorySegment resampler, final String option, final int format) {
        ffmpeg.callInt("av_opt_set_sample_fmt", resampler, arena.allocateFrom(option), format, 0);
    }

    private PcmChunk convert(final MemorySegment resampler, final MemorySegment planes, final int inputSamples) {
        final var capacity = ffmpeg.callInt("swr_get_out_samples", resampler, inputSamples);
        if (output.byteSize() < 2L * capacity) {
            output = Arena.ofAuto().allocate(2L * capacity);
            outputPlanes.set(ADDRESS, 0, output);
        }
        final var count = ffmpeg.callInt("swr_convert", resampler, outputPlanes, capacity, planes, inputSamples);
        if (count <= 0) {
            return null;
        }
        final var samples = new short[count];
        for (var i = 0; i < count; i++) {
            samples[i] = output.getAtIndex(JAVA_SHORT, i);
        }
        return new PcmChunk(samples);
    }

    @Override
    public void close() {
        ffmpeg.callVoid("swr_free", resamplerHolder);
        ffmpeg.callVoid("av_frame_free", frameHolder);
        ffmpeg.callVoid("av_packet_free", packetHolder);
        ffmpeg.callVoid("avcodec_free_context", decoderHolder);
        arena.close();
    }
}
