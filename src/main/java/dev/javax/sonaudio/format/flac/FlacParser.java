package dev.javax.sonaudio.format.flac;

import dev.javax.bitstream.BitInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.FieldUtils;
import dev.javax.sonaudio.format.Format;
import dev.javax.sonaudio.format.MD5Checksum;
import dev.javax.sonaudio.format.Parser;
import dev.javax.sonaudio.metadata.RawMetadata;
import dev.javax.sonaudio.metadata.RawMetadataBuilder;

public final class FlacParser implements Parser {

    static final byte[] FLAC_HEADER = "fLaC".getBytes(StandardCharsets.US_ASCII);

    static final int MEDIA_CATALOG_NUMBER_SIZE = 128;
    static final int ISRC_SIZE = 12;

    @Override
    public Format format() {
        return Format.FLAC;
    }

    @Override
    public ParsedFlac parse(final SeekableByteChannel channel) throws IOException, InvalidFormatException {
        // FLAC keeps all its metadata before its audio, so it reads straight through.
        final var inputStream = Channels.newInputStream(channel);
        final var flacMetadata = parseAllMetadata(inputStream);
        final var streamInfo = flacMetadata
                .streamInfo()
                .orElseThrow(() -> new InvalidFormatException("FLAC file is missing its STREAMINFO block!"));
        return new ParsedFlac(rawMetadata(flacMetadata, streamInfo), new FlacDecoder(streamInfo, inputStream));
    }

    private static RawMetadata rawMetadata(final FlacMetadata flacMetadata, final StreamInfo streamInfo) {
        final var comments = flacMetadata.vorbisComments();

        final var rawMetadata = new RawMetadataBuilder(streamInfo.duration());

        for (final var comment : comments) {
            for (final var field : comment.fields()) {

                final var fieldName = field.name().toLowerCase();
                final var flacTag = FlacTag.fromFieldName(fieldName);
                if (flacTag != null) {
                    rawMetadata.addTag(flacTag.getTag(), flacTag.toTagValue(field.content()));
                } else {
                    rawMetadata.addUnknownTag(fieldName, field.content());
                }
            }
        }

        return rawMetadata.build();
    }

    FlacMetadata parseAllMetadata(final InputStream inputStream) throws IOException, InvalidFormatException {
        final var dataStream = new DataInputStream(inputStream);
        // Validate FLAC header
        validateFlacHeader(dataStream);

        final Map<BlockType, List<Block>> metadataBlocks = new EnumMap<>(BlockType.class);
        // available() can't tell whether more blocks follow, so read until the block marked last.
        Block block;
        do {
            block = readBlock(dataStream);
            metadataBlocks
                    .computeIfAbsent(block.header().blockType(), k -> new ArrayList<>())
                    .add(block);
        } while (block.header().position() == BlockPosition.NOT_LAST);

        metadataBlocks.replaceAll((_, blocks) -> List.copyOf(blocks));
        return new FlacMetadata(Collections.unmodifiableMap(metadataBlocks));
    }

    private StreamInfo readStreamInfo(final DataInputStream inputStream, final BlockHeader header) throws IOException {
        final var bitInputStream = BitInputStream.wrap(inputStream, ByteOrder.BIG_ENDIAN);

        final int minBlockSize = (int) bitInputStream.readBits(16);
        final int maxBlockSize = (int) bitInputStream.readBits(16);
        final int minFrameSize = (int) bitInputStream.readBits(24);
        final int maxFrameSize = (int) bitInputStream.readBits(24);

        final int sampleRate = (int) bitInputStream.readBits(20);
        final int numberOfChannels = (int) bitInputStream.readBits(3) + 1;
        final int bitsPerSample = (int) bitInputStream.readBits(5) + 1;
        final long interChannelSamples = bitInputStream.readBits(36);

        final byte[] md5Checksum = inputStream.readNBytes(16);

        return new StreamInfo(
                header,
                new MD5Checksum(md5Checksum),
                minBlockSize,
                maxBlockSize,
                minFrameSize,
                maxFrameSize,
                sampleRate,
                numberOfChannels,
                bitsPerSample,
                interChannelSamples);
    }

    private Padding readPadding(final DataInputStream inputStream, final BlockHeader header) throws IOException {
        inputStream.skipNBytes(header.size());
        return new Padding(header);
    }

    private Application readApplication(final DataInputStream inputStream, final BlockHeader header)
            throws IOException, InvalidFormatException {
        final var applicationId = inputStream.readInt();
        final var data = readBytes(inputStream, header.size() - 4, header);
        return new Application(header, applicationId, data);
    }

    private SeekTable readSeekTable(final DataInputStream inputStream, final BlockHeader header) throws IOException {
        final var numberOfSeekPoints = header.size() / 18;

        final var seekPoints = new ArrayList<SeekPoint>(numberOfSeekPoints);
        for (int i = 0; i < numberOfSeekPoints; i++) {
            final var sampleNumber = inputStream.readLong();
            final var frameOffset = inputStream.readLong();
            final var samplesInFrame = inputStream.readShort();

            final var seekPoint = new SeekPoint(sampleNumber, frameOffset, samplesInFrame);
            seekPoints.add(seekPoint);
        }

        return new SeekTable(header, List.copyOf(seekPoints));
    }

    private VorbisComment readVorbisComment(final DataInputStream inputStream, final BlockHeader header)
            throws IOException, InvalidFormatException {
        // Little endian u32
        final var length = Integer.reverseBytes(inputStream.readInt());
        final var vendor = readUtf8(inputStream, length, header);

        final var numberOfFields = Integer.reverseBytes(inputStream.readInt());
        // Each field starts with its 4-byte length, so more fields than that can't fit in the block.
        if (numberOfFields < 0 || numberOfFields > header.size() / 4) {
            throw new InvalidFormatException("Incorrect number of Vorbis comment fields: " + numberOfFields);
        }

        final var fields = new ArrayList<Field>(numberOfFields);
        for (int i = 0; i < numberOfFields; i++) {
            // Little endian
            final var fieldLength = Integer.reverseBytes(inputStream.readInt());
            final var fieldContent = readUtf8(inputStream, fieldLength, header);

            final var fieldEndIdx = fieldContent.indexOf("=");
            if (fieldEndIdx < 0) {
                throw new InvalidFormatException("Vorbis comment field without a '=': " + fieldContent);
            }
            final var name = fieldContent.substring(0, fieldEndIdx);
            final var content = fieldContent.substring(fieldEndIdx + 1);

            fields.add(new Field(name, content));
        }

        return new VorbisComment(header, vendor, List.copyOf(fields));
    }

    private CueSheet readCueSheet(final DataInputStream inputStream, final BlockHeader header) throws IOException {
        final var mediaCatalogNumber = readAscii(inputStream, MEDIA_CATALOG_NUMBER_SIZE);
        final var leadInSamples = inputStream.readLong();

        final var bitInputStream = BitInputStream.wrap(inputStream, ByteOrder.BIG_ENDIAN);
        final var medium = CueSheetMedium.values()[(int) bitInputStream.readBits(1)];
        // Reserved: the rest of this byte plus 258 more
        bitInputStream.readBits(7);
        inputStream.skipNBytes(258);

        final var numberOfTracks = inputStream.readUnsignedByte();
        final var tracks = new ArrayList<CueSheetTrack>(numberOfTracks);
        for (int i = 0; i < numberOfTracks; i++) {
            tracks.add(readCueSheetTrack(inputStream));
        }

        return new CueSheet(header, mediaCatalogNumber, leadInSamples, medium, List.copyOf(tracks));
    }

    private CueSheetTrack readCueSheetTrack(final DataInputStream inputStream) throws IOException {
        final var offset = inputStream.readLong();
        final var number = inputStream.readUnsignedByte();
        final var isrc = readAscii(inputStream, ISRC_SIZE);

        final var bitInputStream = BitInputStream.wrap(inputStream, ByteOrder.BIG_ENDIAN);
        final var trackType = TrackType.values()[(int) bitInputStream.readBits(1)];
        final var preEmphasis = PreEmphasis.values()[(int) bitInputStream.readBits(1)];
        // Reserved: the rest of this byte plus 13 more
        bitInputStream.readBits(6);
        inputStream.skipNBytes(13);

        final var numberOfIndexPoints = inputStream.readUnsignedByte();
        final var indexPoints = new ArrayList<CueSheetIndexPoint>(numberOfIndexPoints);
        for (int i = 0; i < numberOfIndexPoints; i++) {
            final var indexOffset = inputStream.readLong();
            final var indexNumber = inputStream.readUnsignedByte();
            // Reserved
            inputStream.skipNBytes(3);

            indexPoints.add(new CueSheetIndexPoint(indexOffset, indexNumber));
        }

        return new CueSheetTrack(offset, number, isrc, trackType, preEmphasis, indexPoints);
    }

    private Picture readPicture(final DataInputStream inputStream, final BlockHeader header)
            throws IOException, InvalidFormatException {
        // Unlike Vorbis comment lengths, picture lengths are big endian.
        final var pictureType = getPictureType(inputStream.readInt());
        final var mediaTypeLength = inputStream.readInt();
        final var mediaType = new String(readBytes(inputStream, mediaTypeLength, header), StandardCharsets.US_ASCII);
        final var descriptionLength = inputStream.readInt();
        final var description = readUtf8(inputStream, descriptionLength, header);

        final var width = inputStream.readInt();
        final var height = inputStream.readInt();
        final var colorDepth = inputStream.readInt();
        final var numberOfColors = inputStream.readInt();

        final var dataLength = inputStream.readInt();
        final var data = readBytes(inputStream, dataLength, header);

        return new Picture(
                header, pictureType, mediaType, description, width, height, colorDepth, numberOfColors, data);
    }

    private Block readBlock(final DataInputStream inputStream) throws IOException, InvalidFormatException {
        final var header = readBlockHeader(inputStream);

        return switch (header.blockType()) {
            case STREAM_INFO -> readStreamInfo(inputStream, header);
            case PADDING -> readPadding(inputStream, header);
            case APPLICATION -> readApplication(inputStream, header);
            case SEEK_TABLE -> readSeekTable(inputStream, header);
            case VORBIS_COMMENT -> readVorbisComment(inputStream, header);
            case CUESHEET -> readCueSheet(inputStream, header);
            case PICTURE -> readPicture(inputStream, header);
        };
    }

    private String readUtf8(final DataInputStream inputStream, final int length, final BlockHeader header)
            throws IOException, InvalidFormatException {
        return new String(readBytes(inputStream, length, header), StandardCharsets.UTF_8);
    }

    private byte[] readBytes(final DataInputStream inputStream, final int length, final BlockHeader header)
            throws IOException, InvalidFormatException {
        if (length < 0 || length > header.size()) {
            throw new InvalidFormatException(length + " bytes in a block of " + header.size());
        }
        return inputStream.readNBytes(length);
    }

    // Fixed-size ASCII fields are padded with NUL bytes, or are all NUL when empty.
    private String readAscii(final DataInputStream inputStream, final int size) throws IOException {
        final var bits = BitInputStream.wrap(inputStream, ByteOrder.BIG_ENDIAN);
        return FieldUtils.readNulPaddedString(bits, size);
    }

    private void validateFlacHeader(final DataInputStream inputStream) throws IOException, InvalidFormatException {
        final var header = inputStream.readNBytes(4);
        if (!Arrays.equals(header, FLAC_HEADER)) {
            throw new InvalidFormatException("Not a FLAC file!");
        }
    }

    private BlockHeader readBlockHeader(final DataInputStream inputStream) throws IOException, InvalidFormatException {
        final var bitStream = BitInputStream.wrap(inputStream, ByteOrder.BIG_ENDIAN);

        final var position = BlockPosition.values()[(int) bitStream.readBits(1)];
        final var blockType = getBlockType((int) bitStream.readBits(7));

        final var size = bitStream.readBits(24);

        return new BlockHeader(position, blockType, (int) size);
    }

    private static BlockType getBlockType(final int headerByte) throws InvalidFormatException {
        return switch (headerByte) {
            case 0 -> BlockType.STREAM_INFO;
            case 1 -> BlockType.PADDING;
            case 2 -> BlockType.APPLICATION;
            case 3 -> BlockType.SEEK_TABLE;
            case 4 -> BlockType.VORBIS_COMMENT;
            case 5 -> BlockType.CUESHEET;
            case 6 -> BlockType.PICTURE;
            default -> throw new InvalidFormatException("Incorrect block type: " + headerByte);
        };
    }

    private static PictureType getPictureType(final int value) throws InvalidFormatException {
        final var pictureTypes = PictureType.values();
        if (value < 0 || value >= pictureTypes.length) {
            throw new InvalidFormatException("Incorrect picture type: " + value);
        }
        return pictureTypes[value];
    }
}
