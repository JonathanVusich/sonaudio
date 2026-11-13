package dev.javax.sonaudio.format.alac;

import dev.javax.bitstream.BitInputStream;
import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.Format;
import dev.javax.sonaudio.format.Parser;
import dev.javax.sonaudio.internal.BoundedInputStream;
import dev.javax.sonaudio.internal.PicardNames;
import dev.javax.sonaudio.metadata.RawMetadata;
import dev.javax.sonaudio.metadata.RawMetadataBuilder;
import dev.javax.sonaudio.metadata.Tag;
import dev.javax.sonaudio.metadata.TagValue;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.SequenceInputStream;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the metadata of an MPEG-4 file's ALAC audio, and hands out an {@link AlacDecoder} of its samples. A malformed
 * file throws {@link dev.javax.sonaudio.exception.InvalidFormatException}.
 *
 * <p>It reads the boxes as FFmpeg's MOV demuxer does: the {@code moov} box wherever it is in the file, with its
 * {@code mvhd}, and in the first audio track the {@code mdhd}, the {@code stsd} entry with its {@code alac} box (also
 * in the {@code wave} box that QuickTime wraps it in) and the sample tables {@code stts}, {@code stsc}, {@code stsz}
 * or {@code stz2}, and {@code stco} or {@code co64}, and the edit list {@code elst}. Fragmented files, whose samples
 * are in {@code moof} boxes, aren't read. The tags are those of {@code moov/udta/meta/ilst}, and iTunes' gapless
 * {@code iTunSMPB} tag trims the end of the audio as it does in FFmpeg.
 */
public final class AlacParser implements Parser {

    private static final int BOX_HEADER_SIZE = 8;
    private static final int LARGE_SIZE_HEADER_SIZE = 16;
    // A box size of 1 says its size follows, in 64 bits, and of 0 that it goes on to the end of what holds it.
    private static final long LARGE_SIZE = 1;
    private static final long TO_END_SIZE = 0;
    private static final int FILE_TYPE_LIMIT = 64 * 1024;
    private static final int MOOV_BUFFER_SIZE = 64 * 1024;
    private static final int FLAGS_BITS = 24;
    private static final int TYPE_MASK = 0xFFFFFF;
    // The most samples per channel in a packet that FFmpeg's decoder takes.
    private static final long MAX_FRAME_LENGTH = 4096L * 4096L;
    private static final int COOKIE_FIELDS_SIZE = 24;
    // An alac box's version and flags, then its cookie.
    private static final int COOKIE_BOX_SIZE = Integer.BYTES + COOKIE_FIELDS_SIZE;
    private static final int MAX_SAMPLE_ENTRIES = 1024;
    // A sample entry's reserved bytes and data reference index, then its audio fields up to the sample rate.
    private static final int ENTRY_COMMON_SIZE = 8;
    private static final int ENTRY_AUDIO_FIELDS = 2 + 6 + 2 + 2 + 2 + 2 + 4;
    private static final int ENTRY_VERSION_1_FIELDS = 16;
    private static final int ENTRY_VERSION_2_FIELDS = 36;
    // FFmpeg clips an stts duration above this, which only a file that has it negative has.
    private static final long MAX_SAMPLE_DURATION = 0xFFFF_FFFFL - 48000 * 10;
    private static final int UTF8_DATA = 1;
    private static final int IMPLICIT_DATA = 0;
    private static final int NUMBER_DATA_SIZE = 6;
    private static final String QUICKTIME_BRAND = "qt  ";
    private static final String GAPLESS_NAME = "iTunSMPB";
    private static final String ORIGINAL_YEAR = "originalyear";
    private static final String DATE_TAG = "date";
    private static final int YEAR_LENGTH = 4;
    private static final int SMPB_FIELDS = 4;
    private static final int HEX = 16;
    private static final String FRAGMENTED = "MPEG-4 file is fragmented, which isn't supported!";

    // Picard keeps the recording's MBID in this atom, and calls it MUSICBRAINZ_TRACKID in FLAC.
    private static final String RECORDING_ID_NAME = "MusicBrainz Track Id";
    private static final String RECORDING_ID_TAG = "musicbrainz_trackid";

    /**
     * Makes a parser, which keeps no state between files, so one can parse every ALAC file, from any thread.
     */
    public AlacParser() {}

    @Override
    public Format format() {
        return Format.ALAC;
    }

    @Override
    public ParsedAlac parse(final SeekableByteChannel channel) throws IOException, InvalidFormatException {
        final var movie = readMovie(channel);
        final var audio = firstAudioTrack(movie);
        final var track = audio.track();
        if (!track.edits().isEmpty() && movie.timeScale() <= 0) {
            throw new InvalidFormatException("MPEG-4 file has an edit list but no movie time scale!");
        }
        final var gapless = gaplessTags(movie.items(), audio.index());
        final var packets = new AlacPacketPlanner(track, movie.timeScale()).plan(gapless);
        final var streamInfo = new AlacStreamInfo(track.cookie(), packets.nominalSampleCount());
        final var metadata = rawMetadata(movie.items(), streamInfo.duration());
        return new ParsedAlac(metadata, new AlacDecoder(streamInfo, packets, channel));
    }

    private AudioTrack firstAudioTrack(final Movie movie) throws InvalidFormatException {
        final var tracks = movie.tracks();
        for (var index = 0; index < tracks.size(); index++) {
            switch (tracks.get(index)) {
                case Track.Alac alac -> {
                    return new AudioTrack(alac, index);
                }
                case Track.OtherAudio(final var format) ->
                    throw new InvalidFormatException("MPEG-4 file's audio isn't ALAC, but '" + format + "'!");
                case Track.NotAudio _ -> {}
            }
        }
        throw new InvalidFormatException("MPEG-4 file has no audio track!");
    }

    private RawMetadata rawMetadata(final List<MetadataItem> items, final Duration duration) {
        final var builder = new RawMetadataBuilder(duration);
        for (final var item : items) {
            switch (item) {
                case MetadataItem.Known(final var tag, final var value) -> builder.addTag(tag, value);
                case MetadataItem.Unknown(final var name, final var value, final var trackCount) ->
                    builder.addUnknownTag(name, value);
                // The gapless tag's text is also an unknown tag, which is what is added.
                case MetadataItem.GaplessTag _ -> {}
            }
        }
        return builder.build();
    }

    // iTunes' tag is "<ignored> <priming> <remainder> <samples> ...", in hexadecimal, and applies to the last track
    // read when the tag was. FFmpeg ignores a tag that isn't made of numbers.
    private List<Gapless> gaplessTags(final List<MetadataItem> items, final int audioIndex) {
        final var tags = new ArrayList<Gapless>();
        for (final var item : items) {
            switch (item) {
                case MetadataItem.GaplessTag(final var value, final var trackCount) -> {
                    if (trackCount == audioIndex + 1) {
                        tags.addAll(parseGapless(value));
                    }
                }
                case MetadataItem.Known _, MetadataItem.Unknown _ -> {}
            }
        }
        return tags;
    }

    private List<Gapless> parseGapless(final String value) {
        final var fields = value.trim().split("\\s+");
        if (fields.length < SMPB_FIELDS) {
            return List.of();
        }
        try {
            hexNumber(fields[0]);
            hexNumber(fields[1]);
            final var remainder = hexNumber(fields[2]);
            final var samples = hexNumber(fields[3]);
            return List.of(new Gapless(remainder, samples));
        } catch (final NumberFormatException ignored) {
            return List.of();
        }
    }

    // C's scanf stores a hexadecimal number that is too large for 32 bits as all ones.
    private int hexNumber(final String digits) {
        return (int) Math.min(Long.parseUnsignedLong(digits, HEX), 0xFFFF_FFFFL);
    }

    // The file's boxes, which FFmpeg reads from the first to the end of the file. A moov box may be after the audio.
    private Movie readMovie(final SeekableByteChannel channel) throws IOException, InvalidFormatException {
        final var fileSize = channel.size();
        final var movies = new ArrayList<Movie>();
        var brand = Brand.QUICKTIME;
        var position = 0L;
        while (fileSize - position >= BOX_HEADER_SIZE) {
            final var header = readFileBoxHeader(channel, position, fileSize);
            final var payloadStart = position + header.headerSize();
            switch (header.box()) {
                case FTYP -> {
                    if (movies.isEmpty()) {
                        brand = readFileType(channel, payloadStart, header.payloadSize());
                    }
                }
                case MOOV -> {
                    if (movies.isEmpty()) {
                        movies.add(readMoov(channel, payloadStart, header.payloadSize(), brand));
                    }
                }
                case MOOF -> throw new InvalidFormatException(FRAGMENTED);
                default -> {}
            }
            position = payloadStart + header.payloadSize();
        }
        if (movies.isEmpty()) {
            throw new InvalidFormatException("MPEG-4 file has no moov box!");
        }
        return movies.getFirst();
    }

    private BoxHeader readFileBoxHeader(final SeekableByteChannel channel, final long position, final long fileSize)
            throws IOException, InvalidFormatException {
        channel.position(position);
        final var bits = BitInputStream.wrap(Channels.newInputStream(channel), ByteOrder.BIG_ENDIAN);
        final var header = readBoxHeader(bits, fileSize - position);
        // A truncated mdat is only found out when a packet in it is read.
        final var end = position + header.headerSize() + header.payloadSize();
        if (end > fileSize && header.box() != AlacBox.MDAT) {
            throw new InvalidFormatException("MPEG-4 box runs past the end of the file!");
        }
        return header;
    }

    // The box's size counts its header, and 0 means that it goes to the end of its parent, which has this many bytes
    // left.
    private BoxHeader readBoxHeader(final BitInputStream bits, final long parentRemaining)
            throws IOException, InvalidFormatException {
        final var size = bits.readBits(Integer.SIZE);
        final var box = AlacBox.ofType((int) bits.readBits(Integer.SIZE));
        if (size == LARGE_SIZE) {
            final var largeSize = readLong(bits);
            return checkedHeader(box, LARGE_SIZE_HEADER_SIZE, largeSize);
        }
        if (size == TO_END_SIZE) {
            return checkedHeader(box, BOX_HEADER_SIZE, parentRemaining);
        }
        return checkedHeader(box, BOX_HEADER_SIZE, size);
    }

    private BoxHeader checkedHeader(final AlacBox box, final long headerSize, final long size)
            throws InvalidFormatException {
        if (size < headerSize) {
            throw new InvalidFormatException("MPEG-4 box is smaller than its header!");
        }
        return new BoxHeader(box, headerSize, size - headerSize);
    }

    private long readLong(final BitInputStream bits) throws IOException, InvalidFormatException {
        final var value = readSignedLong(bits);
        if (value < 0) {
            throw new InvalidFormatException("MPEG-4 file has a 64-bit number that is too large!");
        }
        return value;
    }

    private long readSignedLong(final BitInputStream bits) throws IOException {
        final var high = bits.readBits(Integer.SIZE);
        final var low = bits.readBits(Integer.SIZE);
        return (high << Integer.SIZE) | low;
    }

    // FFmpeg takes the file for QuickTime unless its major brand says otherwise, and also if QuickTime is among the
    // compatible brands.
    private Brand readFileType(final SeekableByteChannel channel, final long payloadStart, final long payloadSize)
            throws IOException, InvalidFormatException {
        if (payloadSize < BOX_HEADER_SIZE) {
            throw new InvalidFormatException("MPEG-4 ftyp box is too small!");
        }
        channel.position(payloadStart);
        final var length = (int) Math.min(payloadSize, FILE_TYPE_LIMIT);
        final var brands = Channels.newInputStream(channel).readNBytes(length);
        if (brands.length < length) {
            throw new InvalidFormatException("MPEG-4 file ends in its ftyp box!");
        }
        final var text = new String(brands, StandardCharsets.ISO_8859_1);
        final var major = text.substring(0, Integer.BYTES);
        if (major.equals(QUICKTIME_BRAND) || text.substring(BOX_HEADER_SIZE).contains(QUICKTIME_BRAND)) {
            return Brand.QUICKTIME;
        }
        return Brand.ISO;
    }

    private Movie readMoov(
            final SeekableByteChannel channel, final long payloadStart, final long payloadSize, final Brand brand)
            throws IOException, InvalidFormatException {
        channel.position(payloadStart);
        final var buffered = new BufferedInputStream(Channels.newInputStream(channel), MOOV_BUFFER_SIZE);
        final var region = new BoundedInputStream(buffered, payloadSize);
        final var bits = BitInputStream.wrap(region, ByteOrder.BIG_ENDIAN);
        var timeScale = 0L;
        final var tracks = new ArrayList<Track>();
        final var items = new ArrayList<MetadataItem>();
        while (region.getRemaining() >= BOX_HEADER_SIZE) {
            final var child = nextBox(bits, region);
            switch (child.header().box()) {
                case MVHD -> timeScale = readMovieHeader(child.payload());
                case TRAK -> tracks.add(readTrack(child.payload(), brand));
                case UDTA -> items.addAll(readUserData(child.payload(), tracks.size()));
                case MVEX -> throw new InvalidFormatException(FRAGMENTED);
                default -> {}
            }
            child.finish();
        }
        return new Movie(timeScale, List.copyOf(tracks), List.copyOf(items));
    }

    // Reads the next box's header, and bounds its payload.
    private Child nextBox(final BitInputStream bits, final BoundedInputStream region)
            throws IOException, InvalidFormatException {
        final var remaining = region.getRemaining();
        final var header = readBoxHeader(bits, remaining);
        if (header.headerSize() + header.payloadSize() > remaining) {
            throw new InvalidFormatException("MPEG-4 box runs past the box that holds it!");
        }
        return new Child(header, new BoundedInputStream(region, header.payloadSize()));
    }

    private long readMovieHeader(final BoundedInputStream region) throws IOException, InvalidFormatException {
        final var bits = BitInputStream.wrap(region, ByteOrder.BIG_ENDIAN);
        skipTimes(bits);
        return bits.readBits(Integer.SIZE);
    }

    // The header's version and flags, then its creation and modification times, which are 64 bits in version 1.
    private int skipTimes(final BitInputStream bits) throws IOException, InvalidFormatException {
        final var version = (int) bits.readBits(Byte.SIZE);
        bits.readBits(FLAGS_BITS);
        if (version > 1) {
            throw new InvalidFormatException("MPEG-4 file has a header of version " + version + "!");
        }
        skipBytes(bits, 2 * (Integer.BYTES << version));
        return version;
    }

    private void skipBytes(final BitInputStream bits, final int count) throws IOException {
        for (var i = 0; i < count; i++) {
            bits.readBits(Byte.SIZE);
        }
    }

    private Track readTrack(final BoundedInputStream region, final Brand brand)
            throws IOException, InvalidFormatException {
        final var bits = BitInputStream.wrap(region, ByteOrder.BIG_ENDIAN);
        final var builder = new TrackBuilder();
        while (region.getRemaining() >= BOX_HEADER_SIZE) {
            final var child = nextBox(bits, region);
            switch (child.header().box()) {
                case MDIA -> readMedia(child.payload(), builder, brand);
                case EDTS -> readEdits(child.payload(), builder);
                default -> {}
            }
            child.finish();
        }
        return builder.build();
    }

    private void readMedia(final BoundedInputStream region, final TrackBuilder builder, final Brand brand)
            throws IOException, InvalidFormatException {
        final var bits = BitInputStream.wrap(region, ByteOrder.BIG_ENDIAN);
        while (region.getRemaining() >= BOX_HEADER_SIZE) {
            final var child = nextBox(bits, region);
            switch (child.header().box()) {
                case MDHD -> readMediaHeader(child.payload(), builder);
                case HDLR -> readHandler(child.payload(), builder);
                case MINF -> readMediaInformation(child.payload(), builder, brand);
                default -> {}
            }
            child.finish();
        }
    }

    private void readMediaHeader(final BoundedInputStream region, final TrackBuilder builder)
            throws IOException, InvalidFormatException {
        final var bits = BitInputStream.wrap(region, ByteOrder.BIG_ENDIAN);
        final var version = skipTimes(bits);
        final var timeScale = bits.readBits(Integer.SIZE);
        builder.setMediaHeader(timeScale, readMediaDuration(bits, version));
    }

    // A duration of all ones means that the file doesn't know it.
    private long readMediaDuration(final BitInputStream bits, final int version) throws IOException {
        if (version == 1) {
            final var duration = readSignedLong(bits);
            if (duration == -1L) {
                return 0;
            }
            return duration;
        }
        final var duration = bits.readBits(Integer.SIZE);
        if (duration == 0xFFFF_FFFFL) {
            return 0;
        }
        return duration;
    }

    private void readHandler(final BoundedInputStream region, final TrackBuilder builder) throws IOException {
        final var bits = BitInputStream.wrap(region, ByteOrder.BIG_ENDIAN);
        // Version and flags, then the component type.
        bits.readBits(Integer.SIZE);
        bits.readBits(Integer.SIZE);
        final var subtype = (int) bits.readBits(Integer.SIZE);
        if (subtype == AlacBox.typeOf("soun")) {
            builder.setHandler(TrackBuilder.Handler.SOUND);
        } else if (subtype == AlacBox.typeOf("vide")
                || subtype == AlacBox.typeOf("subp")
                || subtype == AlacBox.typeOf("clcp")) {
            builder.setHandler(TrackBuilder.Handler.VISUAL);
        }
    }

    private void readMediaInformation(final BoundedInputStream region, final TrackBuilder builder, final Brand brand)
            throws IOException, InvalidFormatException {
        final var bits = BitInputStream.wrap(region, ByteOrder.BIG_ENDIAN);
        while (region.getRemaining() >= BOX_HEADER_SIZE) {
            final var child = nextBox(bits, region);
            if (child.header().box() == AlacBox.STBL) {
                readSampleTables(child.payload(), builder, brand);
            }
            child.finish();
        }
    }

    private void readSampleTables(final BoundedInputStream region, final TrackBuilder builder, final Brand brand)
            throws IOException, InvalidFormatException {
        final var bits = BitInputStream.wrap(region, ByteOrder.BIG_ENDIAN);
        final var table = new SampleTableBuilder();
        while (region.getRemaining() >= BOX_HEADER_SIZE) {
            final var child = nextBox(bits, region);
            final var payload = child.payload();
            switch (child.header().box()) {
                case STSD -> readSampleDescriptions(payload, builder, brand);
                case STTS -> readTimes(payload, table);
                case STSC -> readChunks(payload, table);
                case STSZ -> readSizes(payload, table);
                case STZ2 -> readCompactSizes(payload, table);
                case STCO -> readOffsets(payload, table, Integer.SIZE);
                case CO64 -> readOffsets(payload, table, Long.SIZE);
                default -> {}
            }
            child.finish();
        }
        builder.setSampleTable(table.build());
    }

    // Reads the count of a table's entries, which must fit in what the box has left.
    private int readEntryCount(final BitInputStream bits, final BoundedInputStream region, final int entryBytes)
            throws IOException, InvalidFormatException {
        final var count = bits.readBits(Integer.SIZE);
        if (count * entryBytes > region.getRemaining()) {
            throw new InvalidFormatException("MPEG-4 table has more entries than its box has room for!");
        }
        return (int) count;
    }

    private void readTimes(final BoundedInputStream region, final SampleTableBuilder table)
            throws IOException, InvalidFormatException {
        final var bits = BitInputStream.wrap(region, ByteOrder.BIG_ENDIAN);
        bits.readBits(Integer.SIZE);
        final var entries = readEntryCount(bits, region, 2 * Integer.BYTES);
        final var counts = new long[entries];
        final var durations = new long[entries];
        for (var entry = 0; entry < entries; entry++) {
            counts[entry] = bits.readBits(Integer.SIZE);
            durations[entry] = bits.readBits(Integer.SIZE);
            if (durations[entry] > MAX_SAMPLE_DURATION) {
                throw new InvalidFormatException("MPEG-4 stts box has a sample of an impossible duration!");
            }
        }
        table.setTimes(counts, durations);
    }

    private void readChunks(final BoundedInputStream region, final SampleTableBuilder table)
            throws IOException, InvalidFormatException {
        final var bits = BitInputStream.wrap(region, ByteOrder.BIG_ENDIAN);
        bits.readBits(Integer.SIZE);
        final var entries = readEntryCount(bits, region, 3 * Integer.BYTES);
        final var firsts = new int[entries];
        final var samples = new int[entries];
        final var descriptions = new int[entries];
        for (var entry = 0; entry < entries; entry++) {
            firsts[entry] = readInt(bits);
            samples[entry] = readInt(bits);
            descriptions[entry] = readInt(bits);
        }
        table.setChunks(firsts, samples, descriptions);
    }

    private int readInt(final BitInputStream bits) throws IOException, InvalidFormatException {
        final var value = bits.readBits(Integer.SIZE);
        if (value > Integer.MAX_VALUE) {
            throw new InvalidFormatException("MPEG-4 file has a number that is too large!");
        }
        return (int) value;
    }

    private void readSizes(final BoundedInputStream region, final SampleTableBuilder table)
            throws IOException, InvalidFormatException {
        final var bits = BitInputStream.wrap(region, ByteOrder.BIG_ENDIAN);
        bits.readBits(Integer.SIZE);
        final var fixedSize = readInt(bits);
        final var count = bits.readBits(Integer.SIZE);
        if (fixedSize != 0) {
            table.setSizes(fixedSize, count, new int[0]);
            return;
        }
        table.setSizes(0, count, readSampleSizes(bits, region, count, Integer.SIZE));
    }

    private void readCompactSizes(final BoundedInputStream region, final SampleTableBuilder table)
            throws IOException, InvalidFormatException {
        final var bits = BitInputStream.wrap(region, ByteOrder.BIG_ENDIAN);
        bits.readBits(Integer.SIZE);
        bits.readBits(FLAGS_BITS);
        final var fieldSize = (int) bits.readBits(Byte.SIZE);
        if (fieldSize != 4 && fieldSize != 8 && fieldSize != 16 && fieldSize != 32) {
            throw new InvalidFormatException("MPEG-4 stz2 box has samples of " + fieldSize + " bits!");
        }
        final var count = bits.readBits(Integer.SIZE);
        table.setSizes(0, count, readSampleSizes(bits, region, count, fieldSize));
    }

    private int[] readSampleSizes(
            final BitInputStream bits, final BoundedInputStream region, final long count, final int fieldSize)
            throws IOException, InvalidFormatException {
        if (count * fieldSize > region.getRemaining() * Byte.SIZE) {
            throw new InvalidFormatException("MPEG-4 file has more sample sizes than its box has room for!");
        }
        final var sizes = new int[(int) count];
        for (var sample = 0; sample < sizes.length; sample++) {
            sizes[sample] = (int) bits.readBits(fieldSize);
        }
        return sizes;
    }

    private void readOffsets(final BoundedInputStream region, final SampleTableBuilder table, final int offsetBits)
            throws IOException, InvalidFormatException {
        final var bits = BitInputStream.wrap(region, ByteOrder.BIG_ENDIAN);
        bits.readBits(Integer.SIZE);
        final var offsets = new long[readEntryCount(bits, region, offsetBits / Byte.SIZE)];
        for (var chunk = 0; chunk < offsets.length; chunk++) {
            if (offsetBits == Long.SIZE) {
                offsets[chunk] = readLong(bits);
            } else {
                offsets[chunk] = bits.readBits(Integer.SIZE);
            }
        }
        table.setOffsets(offsets);
    }

    // The sample entries: only the first is read, and a track with more isn't decoded.
    private void readSampleDescriptions(final BoundedInputStream region, final TrackBuilder builder, final Brand brand)
            throws IOException, InvalidFormatException {
        final var bits = BitInputStream.wrap(region, ByteOrder.BIG_ENDIAN);
        final var payloadSize = region.getRemaining();
        final var version = (int) (bits.readBits(Integer.SIZE) >>> FLAGS_BITS);
        final var entries = bits.readBits(Integer.SIZE);
        if (entries <= 0 || entries > payloadSize / BOX_HEADER_SIZE || entries > MAX_SAMPLE_ENTRIES) {
            throw new InvalidFormatException("MPEG-4 stsd box has " + entries + " sample entries!");
        }
        for (var entry = 0; entry < entries; entry++) {
            final var size = bits.readBits(Integer.SIZE);
            final var format = (int) bits.readBits(Integer.SIZE);
            if (size < BOX_HEADER_SIZE || size - BOX_HEADER_SIZE > region.getRemaining()) {
                throw new InvalidFormatException("MPEG-4 sample entry doesn't fit in its stsd box!");
            }
            final var payload = new BoundedInputStream(region, size - BOX_HEADER_SIZE);
            if (entry == 0) {
                builder.setSampleEntries((int) entries, formatName(format));
                readFirstEntry(payload, format, version, builder, brand);
            }
            payload.skipAvailableBytes(payload.getRemaining());
        }
    }

    private String formatName(final int format) {
        final var bytes = new byte[Integer.BYTES];
        for (var i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (format >>> (Byte.SIZE * (bytes.length - 1 - i)));
        }
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private void readFirstEntry(
            final BoundedInputStream region,
            final int format,
            final int stsdVersion,
            final TrackBuilder builder,
            final Brand brand)
            throws IOException, InvalidFormatException {
        if (format != AlacBox.ALAC.type()) {
            return;
        }
        if (region.getRemaining() < ENTRY_COMMON_SIZE + ENTRY_AUDIO_FIELDS) {
            throw new InvalidFormatException("ALAC sample entry is too small!");
        }
        final var bits = BitInputStream.wrap(region, ByteOrder.BIG_ENDIAN);
        skipBytes(bits, ENTRY_COMMON_SIZE);
        final var version = (int) bits.readBits(Short.SIZE);
        skipBytes(bits, ENTRY_AUDIO_FIELDS - Short.BYTES);
        // QuickTime's sound description has more fields in versions 1 and 2, which a file of its own kind has.
        if (brand == Brand.QUICKTIME || (stsdVersion == 0 && version > 0)) {
            skipQuickTimeFields(bits, version);
        }
        while (region.getRemaining() >= BOX_HEADER_SIZE) {
            final var child = nextBox(bits, region);
            switch (child.header().box()) {
                case ALAC -> readCookieBox(child.payload(), builder);
                case WAVE -> readWave(child.payload(), builder);
                default -> {}
            }
            child.finish();
        }
    }

    private void skipQuickTimeFields(final BitInputStream bits, final int version) throws IOException {
        switch (version) {
            case 1 -> skipBytes(bits, ENTRY_VERSION_1_FIELDS);
            case 2 -> skipBytes(bits, ENTRY_VERSION_2_FIELDS);
            default -> {}
        }
    }

    // FFmpeg's decoder is given the first alac box it finds.
    private void readCookieBox(final BoundedInputStream region, final TrackBuilder builder)
            throws IOException, InvalidFormatException {
        if (builder.hasCookie()) {
            return;
        }
        if (region.getRemaining() < COOKIE_BOX_SIZE) {
            throw new InvalidFormatException("ALAC box is too small to hold its cookie!");
        }
        final var bits = BitInputStream.wrap(region, ByteOrder.BIG_ENDIAN);
        bits.readBits(Integer.SIZE);
        builder.setCookie(readCookie(bits));
    }

    // QuickTime's wave box holds an frma box and the alac box, or else the cookie's fields alone, which FFmpeg tells
    // by whether the box starts with an frma box.
    private void readWave(final BoundedInputStream region, final TrackBuilder builder)
            throws IOException, InvalidFormatException {
        final var size = region.getRemaining();
        if (size <= BOX_HEADER_SIZE) {
            return;
        }
        final var start = region.readNBytes(BOX_HEADER_SIZE);
        final var whole = new SequenceInputStream(new ByteArrayInputStream(start), region);
        final var bounded = new BoundedInputStream(whole, size);
        final var bits = BitInputStream.wrap(bounded, ByteOrder.BIG_ENDIAN);
        if (size >= COOKIE_FIELDS_SIZE && !startsWithFrma(start, size - BOX_HEADER_SIZE) && !builder.hasCookie()) {
            builder.setCookie(readCookie(bits));
            return;
        }
        while (bounded.getRemaining() >= BOX_HEADER_SIZE) {
            final var child = nextBox(bits, bounded);
            switch (child.header().box()) {
                case ALAC -> readCookieBox(child.payload(), builder);
                default -> {}
            }
            child.finish();
        }
    }

    private boolean startsWithFrma(final byte[] start, final long remaining) {
        final var header = new String(start, StandardCharsets.ISO_8859_1);
        final var boxSize = ((start[0] & 0xFFL) << 24)
                | ((start[1] & 0xFFL) << 16)
                | ((start[2] & 0xFFL) << 8)
                | (start[3] & 0xFFL);
        return header.endsWith("frma") && boxSize <= remaining && boxSize >= BOX_HEADER_SIZE;
    }

    private AlacCookie readCookie(final BitInputStream bits) throws IOException, InvalidFormatException {
        final var frameLength = bits.readBits(Integer.SIZE);
        // The compatible version.
        bits.readBits(Byte.SIZE);
        final var bitsPerSample = (int) bits.readBits(Byte.SIZE);
        final var riceHistoryMultiplier = (int) bits.readBits(Byte.SIZE);
        final var riceInitialHistory = (int) bits.readBits(Byte.SIZE);
        final var riceLimit = (int) bits.readBits(Byte.SIZE);
        final var channelCount = (int) bits.readBits(Byte.SIZE);
        final var maxRun = (int) bits.readBits(Short.SIZE);
        final var maxFrameBytes = bits.readBits(Integer.SIZE);
        final var averageBitrate = bits.readBits(Integer.SIZE);
        final var sampleRate = bits.readBits(Integer.SIZE);
        final var cookie = new AlacCookie(
                (int) frameLength,
                bitsPerSample,
                riceHistoryMultiplier,
                riceInitialHistory,
                riceLimit,
                channelCount,
                maxRun,
                maxFrameBytes,
                averageBitrate,
                (int) Math.min(sampleRate, Integer.MAX_VALUE));
        checkCookie(cookie, frameLength, sampleRate);
        return cookie;
    }

    private void checkCookie(final AlacCookie cookie, final long frameLength, final long sampleRate)
            throws InvalidFormatException {
        if (frameLength == 0 || frameLength > MAX_FRAME_LENGTH) {
            throw new InvalidFormatException("ALAC cookie has a frame length of " + frameLength + "!");
        }
        final var depth = cookie.bitsPerSample();
        if (depth != 16 && depth != 20 && depth != 24 && depth != 32) {
            throw new InvalidFormatException("ALAC cookie has samples of " + depth + " bits, which isn't supported!");
        }
        if (cookie.channelCount() < 1 || cookie.channelCount() > 8) {
            throw new InvalidFormatException("ALAC cookie has " + cookie.channelCount() + " channels!");
        }
        if (sampleRate == 0 || sampleRate > Integer.MAX_VALUE) {
            throw new InvalidFormatException("ALAC cookie has a sample rate of " + sampleRate + "!");
        }
    }

    private void readEdits(final BoundedInputStream region, final TrackBuilder builder)
            throws IOException, InvalidFormatException {
        final var bits = BitInputStream.wrap(region, ByteOrder.BIG_ENDIAN);
        while (region.getRemaining() >= BOX_HEADER_SIZE) {
            final var child = nextBox(bits, region);
            if (child.header().box() == AlacBox.ELST) {
                builder.setEdits(readEditList(child.payload()));
            }
            child.finish();
        }
    }

    private List<Edit> readEditList(final BoundedInputStream region) throws IOException, InvalidFormatException {
        final var bits = BitInputStream.wrap(region, ByteOrder.BIG_ENDIAN);
        final var version = (int) bits.readBits(Byte.SIZE);
        bits.readBits(FLAGS_BITS);
        final var count = bits.readBits(Integer.SIZE);
        if (count * editBytes(version) != region.getRemaining()) {
            throw new InvalidFormatException("MPEG-4 elst box doesn't have the " + count + " entries it says!");
        }
        final var edits = new ArrayList<Edit>();
        for (var entry = 0; entry < count; entry++) {
            edits.add(readEdit(bits, version));
        }
        return List.copyOf(edits);
    }

    // An entry is a duration, a media time and a media rate, and the first two are 64 bits in version 1.
    private int editBytes(final int version) {
        if (version == 1) {
            return 5 * Integer.BYTES;
        }
        return 3 * Integer.BYTES;
    }

    // The media time of an empty edit is -1.
    private Edit readEdit(final BitInputStream bits, final int version) throws IOException, InvalidFormatException {
        final var duration = editNumber(bits, version);
        final var mediaTime = editNumber(bits, version);
        // The media rate.
        bits.readBits(Integer.SIZE);
        if (duration < 0) {
            throw new InvalidFormatException("MPEG-4 elst box has an edit of a negative duration!");
        }
        if (mediaTime == -1) {
            return new Edit.Empty(duration);
        }
        return new Edit.Media(duration, mediaTime);
    }

    private long editNumber(final BitInputStream bits, final int version) throws IOException {
        if (version == 1) {
            return readSignedLong(bits);
        }
        return (int) bits.readBits(Integer.SIZE);
    }

    private List<MetadataItem> readUserData(final BoundedInputStream region, final int trackCount)
            throws IOException, InvalidFormatException {
        final var bits = BitInputStream.wrap(region, ByteOrder.BIG_ENDIAN);
        final var items = new ArrayList<MetadataItem>();
        while (region.getRemaining() >= BOX_HEADER_SIZE) {
            final var child = nextBox(bits, region);
            if (child.header().box() == AlacBox.META) {
                items.addAll(readMeta(child.payload(), trackCount));
            }
            child.finish();
        }
        return items;
    }

    private List<MetadataItem> readMeta(final BoundedInputStream region, final int trackCount)
            throws IOException, InvalidFormatException {
        final var bits = BitInputStream.wrap(region, ByteOrder.BIG_ENDIAN);
        final var items = new ArrayList<MetadataItem>();
        if (region.getRemaining() < Integer.BYTES) {
            return items;
        }
        // The version and flags of a full box.
        bits.readBits(Integer.SIZE);
        while (region.getRemaining() >= BOX_HEADER_SIZE) {
            final var child = nextBox(bits, region);
            if (child.header().box() == AlacBox.ILST) {
                items.addAll(readItemList(child.payload(), trackCount));
            }
            child.finish();
        }
        return items;
    }

    private List<MetadataItem> readItemList(final BoundedInputStream region, final int trackCount)
            throws IOException, InvalidFormatException {
        final var bits = BitInputStream.wrap(region, ByteOrder.BIG_ENDIAN);
        final var items = new ArrayList<MetadataItem>();
        while (region.getRemaining() >= BOX_HEADER_SIZE) {
            final var child = nextBox(bits, region);
            items.addAll(readItem(child.header().box(), child.payload(), trackCount));
            child.finish();
        }
        return items;
    }

    // The items of the tags sonaudio reads. Every other item of the list is skipped, as the format lets a reader do.
    private List<MetadataItem> readItem(final AlacBox box, final BoundedInputStream region, final int trackCount)
            throws IOException, InvalidFormatException {
        return switch (box) {
            case TITLE -> textItems(region, Tag.TRACK_TITLE);
            case ARTIST -> textItems(region, Tag.TRACK_ARTIST);
            case ALBUM_ARTIST -> textItems(region, Tag.ALBUM_ARTIST);
            case ALBUM -> textItems(region, Tag.RELEASE_TITLE);
            case COMPOSER -> textItems(region, Tag.COMPOSER);
            case DATE -> dateItems(region);
            case TRACK_NUMBER -> numberItems(region, Tag.TRACK_NUMBER);
            case DISC_NUMBER -> numberItems(region, Tag.DISC_NUMBER);
            case FREEFORM -> freeformItems(region, trackCount);
            default -> List.of();
        };
    }

    private List<MetadataItem> textItems(final BoundedInputStream region, final Tag tag)
            throws IOException, InvalidFormatException {
        final var items = new ArrayList<MetadataItem>();
        for (final var atom : readDataAtoms(region)) {
            items.add(new MetadataItem.Known(tag, new TagValue.Str(utf8Text(atom))));
        }
        return items;
    }

    private List<MetadataItem> dateItems(final BoundedInputStream region) throws IOException, InvalidFormatException {
        final var items = new ArrayList<MetadataItem>();
        for (final var atom : readDataAtoms(region)) {
            items.add(new MetadataItem.Unknown(DATE_TAG, utf8Text(atom), 0));
        }
        return items;
    }

    // The track or disc number is 2 bytes of padding, a 2-byte number and a 2-byte total, which 0 leaves out.
    private List<MetadataItem> numberItems(final BoundedInputStream region, final Tag tag)
            throws IOException, InvalidFormatException {
        final var items = new ArrayList<MetadataItem>();
        for (final var atom : readDataAtoms(region)) {
            final var value = atom.value();
            if (value.length < NUMBER_DATA_SIZE) {
                throw new InvalidFormatException("MPEG-4 track or disc number is too short!");
            }
            final var number = ((value[2] & 0xFF) << Byte.SIZE) | (value[3] & 0xFF);
            if (number > 0) {
                items.add(new MetadataItem.Known(tag, new TagValue.Int(number)));
            }
        }
        return items;
    }

    private String utf8Text(final DataAtom atom) throws InvalidFormatException {
        if (atom.type() != UTF8_DATA) {
            throw new InvalidFormatException("MPEG-4 text tag has data of type " + atom.type() + ", not UTF-8!");
        }
        return new String(atom.value(), StandardCharsets.UTF_8);
    }

    private List<DataAtom> readDataAtoms(final BoundedInputStream region) throws IOException, InvalidFormatException {
        final var bits = BitInputStream.wrap(region, ByteOrder.BIG_ENDIAN);
        final var atoms = new ArrayList<DataAtom>();
        while (region.getRemaining() >= BOX_HEADER_SIZE) {
            final var child = nextBox(bits, region);
            if (child.header().box() == AlacBox.DATA) {
                atoms.add(readDataAtom(child.payload()));
            }
            child.finish();
        }
        return atoms;
    }

    private DataAtom readDataAtom(final BoundedInputStream region) throws IOException, InvalidFormatException {
        if (region.getRemaining() < BOX_HEADER_SIZE) {
            throw new InvalidFormatException("MPEG-4 data box is too small!");
        }
        final var bits = BitInputStream.wrap(region, ByteOrder.BIG_ENDIAN);
        final var type = (int) (bits.readBits(Integer.SIZE) & TYPE_MASK);
        // The locale.
        bits.readBits(Integer.SIZE);
        return new DataAtom(type, region.readNBytes((int) region.getRemaining()));
    }

    // A freeform item has a mean (the owner, which FFmpeg doesn't look at), a name and its data.
    private List<MetadataItem> freeformItems(final BoundedInputStream region, final int trackCount)
            throws IOException, InvalidFormatException {
        final var bits = BitInputStream.wrap(region, ByteOrder.BIG_ENDIAN);
        final var names = new ArrayList<String>();
        final var atoms = new ArrayList<DataAtom>();
        var means = 0;
        while (region.getRemaining() >= BOX_HEADER_SIZE) {
            final var child = nextBox(bits, region);
            switch (child.header().box()) {
                case MEAN -> means++;
                case NAME -> names.add(readFullBoxText(child.payload()));
                case DATA -> atoms.add(readDataAtom(child.payload()));
                default -> {}
            }
            child.finish();
        }
        if (means != 1 || names.size() != 1 || atoms.isEmpty()) {
            throw new InvalidFormatException("MPEG-4 freeform item doesn't have a mean, a name and data!");
        }
        return freeformValues(names.getFirst(), atoms, trackCount);
    }

    private String readFullBoxText(final BoundedInputStream region) throws IOException, InvalidFormatException {
        if (region.getRemaining() < Integer.BYTES) {
            throw new InvalidFormatException("MPEG-4 freeform item has a name that is too small!");
        }
        final var bits = BitInputStream.wrap(region, ByteOrder.BIG_ENDIAN);
        bits.readBits(Integer.SIZE);
        return new String(region.readNBytes((int) region.getRemaining()), StandardCharsets.UTF_8);
    }

    // Text is UTF-8, or implicit; a freeform item of any other type isn't text, so it isn't a tag.
    private List<MetadataItem> freeformValues(final String name, final List<DataAtom> atoms, final int trackCount)
            throws InvalidFormatException {
        final var items = new ArrayList<MetadataItem>();
        final var key = name.toLowerCase(Locale.ROOT);
        for (final var atom : atoms) {
            if (atom.type() != UTF8_DATA && atom.type() != IMPLICIT_DATA) {
                continue;
            }
            final var text = new String(atom.value(), StandardCharsets.UTF_8);
            if (key.equals(ORIGINAL_YEAR)) {
                items.add(new MetadataItem.Known(Tag.RELEASE_YEAR, new TagValue.Int(year(text))));
            } else {
                items.add(new MetadataItem.Unknown(tagName(name), text, trackCount));
            }
            // FFmpeg takes the name's case as it is.
            if (name.equals(GAPLESS_NAME)) {
                items.add(new MetadataItem.GaplessTag(text, trackCount));
            }
        }
        return items;
    }

    private String tagName(final String name) {
        if (name.equals(RECORDING_ID_NAME)) {
            return RECORDING_ID_TAG;
        }
        return PicardNames.userTextName(name);
    }

    // A date starts with the year: yyyy, yyyy-MM-dd and so on.
    private int year(final String date) throws InvalidFormatException {
        try {
            return Integer.parseInt(date.substring(0, Math.min(YEAR_LENGTH, date.length())));
        } catch (final NumberFormatException ignored) {
            throw new InvalidFormatException("MPEG-4 original year isn't a year: '" + date + "'!");
        }
    }
}
