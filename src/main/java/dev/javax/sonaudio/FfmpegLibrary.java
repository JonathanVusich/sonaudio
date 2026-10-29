package dev.javax.sonaudio;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.util.Map.entry;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FFmpeg 7.1's shared libraries (libavformat 61, libavcodec 61, libavutil 59 and libswresample 5), called through
 * Java's foreign function API. Create one and share it: it holds the loaded libraries and the handles of their
 * functions.
 *
 * <p>The structs it reads are laid out as in that release, so it refuses any other major version of libavformat: the
 * offsets below come from {@code offsetof} in its headers, and nothing in the libraries reports them. The JVM needs
 * {@code --enable-native-access=ALL-UNNAMED} (or the module's name) to call them.
 */
public final class FfmpegLibrary {

    static final int AVMEDIA_TYPE_AUDIO = 1;
    static final int AV_SAMPLE_FMT_S16 = 1;
    static final int AVERROR_EOF = -541478725;
    static final int AVERROR_EAGAIN = -11;
    static final int AVERROR_EIO = -5;
    static final int AVERROR_EINVAL = -22;
    static final int AVSEEK_SIZE = 0x10000;
    static final int AVSEEK_FORCE = 0x20000;
    static final long AV_NOPTS_VALUE = Long.MIN_VALUE;
    static final long AV_TIME_BASE = 1_000_000;

    // AVFormatContext
    static final long CONTEXT_PB = 32;
    static final long CONTEXT_NB_STREAMS = 44;
    static final long CONTEXT_STREAMS = 48;
    static final long CONTEXT_DURATION = 104;
    static final long CONTEXT_METADATA = 192;
    // AVStream
    static final long STREAM_CODECPAR = 16;
    static final long STREAM_METADATA = 80;
    // AVCodecParameters
    static final long PARAMETERS_CODEC_ID = 4;
    // AVIOContext
    static final long IO_BUFFER = 8;
    // AVPacket
    static final long PACKET_STREAM_INDEX = 36;
    // AVFrame, whose channel layout is an AVChannelLayout
    static final long FRAME_EXTENDED_DATA = 96;
    static final long FRAME_NB_SAMPLES = 112;
    static final long FRAME_FORMAT = 116;
    static final long FRAME_SAMPLE_RATE = 192;
    static final long FRAME_CH_LAYOUT = 408;
    static final long CHANNEL_LAYOUT_SIZE = 24;
    // AVDictionaryEntry
    static final long ENTRY_KEY = 0;
    static final long ENTRY_VALUE = 8;

    private static final int SUPPORTED_AVFORMAT_MAJOR = 61;
    private static final Map<String, FunctionDescriptor> SIGNATURES = Map.ofEntries(
            entry("avformat_version", FunctionDescriptor.of(JAVA_INT)),
            entry("avformat_alloc_context", FunctionDescriptor.of(ADDRESS)),
            entry(
                    "avio_alloc_context",
                    FunctionDescriptor.of(
                            ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS)),
            entry("avio_context_free", FunctionDescriptor.ofVoid(ADDRESS)),
            entry("av_malloc", FunctionDescriptor.of(ADDRESS, JAVA_LONG)),
            entry("av_free", FunctionDescriptor.ofVoid(ADDRESS)),
            entry("avformat_open_input", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS)),
            entry("avformat_find_stream_info", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
            entry(
                    "av_find_best_stream",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)),
            entry("avformat_close_input", FunctionDescriptor.ofVoid(ADDRESS)),
            entry("av_read_frame", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
            entry("av_packet_alloc", FunctionDescriptor.of(ADDRESS)),
            entry("av_packet_unref", FunctionDescriptor.ofVoid(ADDRESS)),
            entry("av_packet_free", FunctionDescriptor.ofVoid(ADDRESS)),
            entry("av_frame_alloc", FunctionDescriptor.of(ADDRESS)),
            entry("av_frame_unref", FunctionDescriptor.ofVoid(ADDRESS)),
            entry("av_frame_free", FunctionDescriptor.ofVoid(ADDRESS)),
            entry("avcodec_find_decoder", FunctionDescriptor.of(ADDRESS, JAVA_INT)),
            entry("avcodec_alloc_context3", FunctionDescriptor.of(ADDRESS, ADDRESS)),
            entry("avcodec_parameters_to_context", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
            entry("avcodec_open2", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)),
            entry("avcodec_free_context", FunctionDescriptor.ofVoid(ADDRESS)),
            entry("avcodec_send_packet", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
            entry("avcodec_receive_frame", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
            entry("av_dict_get", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT)),
            entry("swr_alloc", FunctionDescriptor.of(ADDRESS)),
            entry("swr_init", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
            entry("swr_free", FunctionDescriptor.ofVoid(ADDRESS)),
            entry("swr_get_out_samples", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)),
            entry("swr_convert", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT)),
            entry("av_channel_layout_default", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT)),
            entry("av_opt_set_chlayout", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT)),
            entry("av_opt_set_int", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT)),
            entry("av_opt_set_sample_fmt", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT)));

    private final Linker linker = Linker.nativeLinker();
    private final SymbolLookup lookup;
    private final Map<String, MethodHandle> handles = new ConcurrentHashMap<>();

    /**
     * @throws IllegalStateException when the libraries aren't installed, or aren't the release this was written for
     */
    public FfmpegLibrary() {
        final var arena = Arena.global();
        this.lookup = SymbolLookup.libraryLookup("libavutil.so.59", arena)
                .or(SymbolLookup.libraryLookup("libswresample.so.5", arena))
                .or(SymbolLookup.libraryLookup("libavcodec.so.61", arena))
                .or(SymbolLookup.libraryLookup("libavformat.so.61", arena));
        final var major = callInt("avformat_version") >> 16;
        if (major != SUPPORTED_AVFORMAT_MAJOR) {
            throw new IllegalStateException("libavformat %d isn't supported".formatted(major));
        }
    }

    int callInt(final String function, final Object... arguments) {
        return (int) call(function, arguments);
    }

    MemorySegment callPointer(final String function, final Object... arguments) {
        return (MemorySegment) call(function, arguments);
    }

    void callVoid(final String function, final Object... arguments) {
        call(function, arguments);
    }

    private Object call(final String function, final Object[] arguments) {
        final var handle = handles.computeIfAbsent(function, this::downcall);
        try {
            return handle.invokeWithArguments(arguments);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Calling %s failed".formatted(function), e);
        }
    }

    private MethodHandle downcall(final String function) {
        final var address = lookup.find(function)
                .orElseThrow(() -> new IllegalStateException("FFmpeg has no function %s".formatted(function)));
        return linker.downcallHandle(address, SIGNATURES.get(function));
    }
}
