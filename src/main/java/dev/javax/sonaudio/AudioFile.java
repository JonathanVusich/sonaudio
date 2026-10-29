package dev.javax.sonaudio;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.SeekableByteChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A file opened by FFmpeg, whatever its format. It holds native memory, so it has to be closed, and it reads the
 * channel it's given until then, which stays the caller's to close.
 *
 * <p>Use it on one thread: FFmpeg's reading and the channel callbacks all happen on the thread that calls it.
 */
public final class AudioFile implements AutoCloseable {

    private static final int IO_BUFFER_SIZE = 32 * 1024;
    private static final long CONTEXT_SIZE = 256;
    private static final long STREAM_SIZE = 128;
    private static final int AV_DICT_IGNORE_SUFFIX = 2;

    private final FfmpegLibrary ffmpeg;
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment contextHolder = arena.allocate(ADDRESS);
    private final MemorySegment ioHolder = arena.allocate(ADDRESS);
    private int streamIndex;
    private MemorySegment parameters;
    private Optional<Duration> duration;
    private Map<String, List<String>> tags;
    private MonoDecoder decoder;

    /**
     * @throws UnreadableAudioException when FFmpeg can't read the file, or finds no audio in it
     */
    public AudioFile(final FfmpegLibrary ffmpeg, final SeekableByteChannel channel) throws UnreadableAudioException {
        this.ffmpeg = ffmpeg;
        try {
            open(channel);
            this.streamIndex = findAudioStream();
            final var stream = stream(streamIndex);
            this.parameters = stream.get(ADDRESS, FfmpegLibrary.STREAM_CODECPAR);
            this.duration = readDuration();
            this.tags = readTags(stream);
        } catch (UnreadableAudioException | RuntimeException e) {
            close();
            throw e;
        }
    }

    /**
     * @return how long FFmpeg says the file is, which it estimates from the bit rate for formats with no exact length
     *     (an MP3 without a VBR header), or empty when it can't tell
     */
    public Optional<Duration> duration() {
        return duration;
    }

    /**
     * @return the file's tags and its audio stream's, under the names FFmpeg gives them whatever the format called
     *     them, each with its values in the order they were read
     */
    public Map<String, List<String>> tags() {
        return tags;
    }

    /**
     * Decodes the audio to 16-bit mono at the given rate, as FFmpeg's libswresample converts it for AcoustID's
     * {@code fpcalc}. The file is read only as far as the chunks are iterated, and they can be iterated once.
     *
     * @throws UnreadableAudioException when FFmpeg has no decoder for the audio
     * @throws IllegalStateException when the audio has already been decoded: the file reads it once
     */
    public Iterable<PcmChunk> decodeMono(final int sampleRate) throws UnreadableAudioException {
        if (decoder != null) {
            throw new IllegalStateException("The audio has already been decoded");
        }
        decoder = new MonoDecoder(ffmpeg, context(), streamIndex, parameters, sampleRate);
        return decoder;
    }

    private void open(final SeekableByteChannel channel) throws UnreadableAudioException {
        final var io = new ChannelIo(channel);
        final var buffer = ffmpeg.callPointer("av_malloc", (long) IO_BUFFER_SIZE);
        final var avio = ffmpeg.callPointer(
                "avio_alloc_context",
                buffer,
                IO_BUFFER_SIZE,
                0,
                MemorySegment.NULL,
                io.readCallback(arena),
                MemorySegment.NULL,
                io.seekCallback(arena));
        ioHolder.set(ADDRESS, 0, avio);
        final var allocated = ffmpeg.callPointer("avformat_alloc_context");
        allocated.reinterpret(CONTEXT_SIZE).set(ADDRESS, FfmpegLibrary.CONTEXT_PB, avio);
        contextHolder.set(ADDRESS, 0, allocated);
        final var opened = ffmpeg.callInt(
                "avformat_open_input", contextHolder, arena.allocateFrom(""), MemorySegment.NULL, MemorySegment.NULL);
        if (opened < 0) {
            throw new UnreadableAudioException("FFmpeg can't read the file (error %d)".formatted(opened));
        }
        if (ffmpeg.callInt("avformat_find_stream_info", context(), MemorySegment.NULL) < 0) {
            throw new UnreadableAudioException("FFmpeg can't read the file's streams");
        }
    }

    private int findAudioStream() throws UnreadableAudioException {
        final var index = ffmpeg.callInt(
                "av_find_best_stream",
                context(),
                FfmpegLibrary.AVMEDIA_TYPE_AUDIO,
                -1,
                -1,
                MemorySegment.NULL,
                0);
        if (index < 0) {
            throw new UnreadableAudioException("FFmpeg found no audio in the file");
        }
        return index;
    }

    private MemorySegment context() {
        return contextHolder.get(ADDRESS, 0).reinterpret(CONTEXT_SIZE);
    }

    private MemorySegment stream(final int index) {
        final var context = context();
        final var count = context.get(JAVA_INT, FfmpegLibrary.CONTEXT_NB_STREAMS);
        final var streams = context.get(ADDRESS, FfmpegLibrary.CONTEXT_STREAMS).reinterpret(8L * count);
        return streams.getAtIndex(ADDRESS, index).reinterpret(STREAM_SIZE);
    }

    private Optional<Duration> readDuration() {
        final var micros = context().get(JAVA_LONG, FfmpegLibrary.CONTEXT_DURATION);
        if (micros == FfmpegLibrary.AV_NOPTS_VALUE) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofNanos(micros * 1000));
    }

    private Map<String, List<String>> readTags(final MemorySegment stream) {
        final var tags = new LinkedHashMap<String, List<String>>();
        addTags(tags, context().get(ADDRESS, FfmpegLibrary.CONTEXT_METADATA));
        addTags(tags, stream.get(ADDRESS, FfmpegLibrary.STREAM_METADATA));
        tags.replaceAll((_, values) -> List.copyOf(values));
        return Collections.unmodifiableMap(tags);
    }

    private void addTags(final Map<String, List<String>> tags, final MemorySegment dictionary) {
        final var empty = arena.allocateFrom("");
        var entry = ffmpeg.callPointer("av_dict_get", dictionary, empty, MemorySegment.NULL, AV_DICT_IGNORE_SUFFIX);
        while (!entry.equals(MemorySegment.NULL)) {
            final var fields = entry.reinterpret(16);
            final var key = text(fields.get(ADDRESS, FfmpegLibrary.ENTRY_KEY));
            final var value = text(fields.get(ADDRESS, FfmpegLibrary.ENTRY_VALUE));
            final var values = tags.computeIfAbsent(key, _ -> new ArrayList<>());
            if (!values.contains(value)) {
                values.add(value);
            }
            entry = ffmpeg.callPointer("av_dict_get", dictionary, empty, entry, AV_DICT_IGNORE_SUFFIX);
        }
    }

    private static String text(final MemorySegment string) {
        return string.reinterpret(Long.MAX_VALUE).getString(0);
    }

    @Override
    public void close() {
        if (decoder != null) {
            decoder.close();
        }
        ffmpeg.callVoid("avformat_close_input", contextHolder);
        final var avio = ioHolder.get(ADDRESS, 0);
        if (!avio.equals(MemorySegment.NULL)) {
            ffmpeg.callVoid("av_free", avio.reinterpret(16).get(ADDRESS, FfmpegLibrary.IO_BUFFER));
            ffmpeg.callVoid("avio_context_free", ioHolder);
        }
        arena.close();
    }
}
